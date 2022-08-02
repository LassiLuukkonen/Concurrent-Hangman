// Two custom classes are needed: Semaphore and of course BlockingEventQueue.

package reactor
import reactor.api.Event
import scala.collection.mutable.Queue
import scala.collection.immutable.Seq


class Semaphore(private val capacity: Int) {

  if (capacity < 0) {
    throw new IllegalArgumentException("capacity must be non-negative.")
  }
  var permits: Int = capacity

  // Signal can't be missed. This is due to synchronization and the wait condition.
  // EITHER 1. the release() method gets executed fully before the acquire() method
  //    --> release() increases permits by one to > 0 (assuming capacity >= 0)
  //    --> the wait condition in acquire() isn't met and wait() doesn't get called
  // OR 2. the acquire() method gets started before the release() method
  //    --> if permits == 0, wait() gets called before notify()
  //    --> else, wait() doesn't get called at all
  @throws[InterruptedException]
  def acquire(): Unit = synchronized {
    while(permits == 0) {
      wait()
    }
    permits -= 1 // Permits is only accessed from synchronized blocks --> no races
  }

  def release(): Unit = synchronized {
    permits += 1
    notify()
    // The wait condition in acquire() has been met because permits was increased
    // in this synchronized method.
  }

  def availablePermits(): Int = synchronized {permits}

  // Acquire all currently available permits.
  // Return the amount of permits acquired.
  def acquireAll(): Int = synchronized {
    val currentPermits: Int = permits
    permits = 0
    return currentPermits
  }

}


final class BlockingEventQueue[T] (private val capacity: Int) {

  if (capacity <= 0) {
    throw new IllegalArgumentException("capacity must be positive.")
  }

  var queue = Queue[Event[T]]()
  var elementSem = new Semaphore(0)
  var capacitySem = new Semaphore(capacity)

  @throws[InterruptedException]
  def enqueue[U <: T](e: Event[U]): Unit = {
    if (e == null) { 
      throw new IllegalArgumentException("e can't be null")
    }
    capacitySem.acquire() // Make sure the queue is not full.
    
    val f: Event[T] = e.asInstanceOf[Event[T]] 
    
    // The synchronized sections in this and the dequeue method below
    // can be executed in any order when the queue is neither full or empty.
    // Otherwise the correct order is enforced by the semaphores.
    this.synchronized {
      queue.enqueue(f) // Only one enqueue method at a time can enqueue elements.
    }
    // Let one waiting elementSem know that there's a new element.
    elementSem.release() 
  }

  @throws[InterruptedException]
  def dequeue: Event[T] = {
    elementSem.acquire(); // Make sure the queue is not empty.
    val returnable: Event[T] = this.synchronized {
      queue.dequeue() // Only one dequeue method at a time can dequeue elements.
    }
    // Let one waiting capacitySem know that there's more space.
    capacitySem.release() 
    return returnable
  }

  def getAll: Seq[Event[T]] = synchronized {
    // Acquire all available permits in the elementSem.
    val acquiredAmount: Int = elementSem.acquireAll() 

    // Get as many elements as possible (= acquiredAmount) from the end of the queue.
    val result: Seq[Event[T]] = queue.slice(queue.length-acquiredAmount, queue.length).toSeq

    // If some number of threads running the dequeue method have previously acquired
    // elementSem but have not dequeued elements from the queue, the remaining elements
    // are left in the queue to be dequeued by the said methods.
    // Note: In this case, the result contains the elements which are not in the middle
    // of being deleted by the dequeue method.
    if (acquiredAmount < queue.length) {
      var remaining: Queue[Event[T]] = queue.slice(0, queue.length-acquiredAmount)
      queue = remaining
    // Otherwise no dequeue methods are waiting to dequeue so the queue is made empty.
    // Note: In this case, the result contains all elements in the queue.
    } else {
      queue = Queue[Event[T]]()
    }

    // Release as much capacity as was acquired.
    for (i <- 1 to acquiredAmount)
      capacitySem.release()

    return result
  }

  // elementSem keeps track of the size of the queue.
  def getSize: Int = elementSem.availablePermits()

  // The capacity is specified through the constructor.
  def getCapacity: Int = capacity 
}
