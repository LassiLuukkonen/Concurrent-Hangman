package reactor

import reactor.api.{Event, EventHandler, Handle}
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import java.util.IllegalFormatException
import javax.naming.InterruptedNamingException

final class Dispatcher(private val queueLength: Int = 10) {
  require(queueLength > 0)

  // Initialize data structures that can only be accessed inside the Dispatcher.
  private var handlersAndThreads: HashMap[EventHandler[_], WorkerThread[_]] = new HashMap()
  // The BlockingEventQueue linearizes the incoming events and works as a buffer
  // allowing the events to be handled in a single thread, sequentially, one-by-one.
  private var events: BlockingEventQueue[Any] = new BlockingEventQueue[Any](queueLength)

  @throws[InterruptedException]
  def handleEvents(): Unit = {
    // The event loop runs for as long as there are handlers (as per spesification).
    while (!(handlersAndThreads.isEmpty)) {
      val event = this.select // Wait here until a new event is available.
      val handler = event.getHandler
      if (handlersAndThreads.contains(handler)) {
        event.handle() // If the handler of the event has been registered (added), handle the event.
      } else {
        // Events are not read before the registration of the corresponding EventHandler.
        // Moreover, it makes sense to let the programmer know that some Events are not handled.
        throw new Exception("The corresponding EventHandler hasn't been added.") // Misuse of the API
      }
    }
  }

  @throws[InterruptedException]
  def select[_]: Event[_] = events.dequeue // The dequeue method blocks until a new event is available.

  def addHandler[T](handler: EventHandler[T]): Unit = {
    // The same handler can't be added more than once (as per spesification).
    // However, a handler can be added again after it's been removed (why not?).
    if (handlersAndThreads.contains(handler)) {
      throw new IllegalArgumentException("Given handler already exists.") // Misuse of the API
    }

    // Add the handler and create a new thread for it.
    val thread = new WorkerThread(handler)
    thread.start()
    handlersAndThreads.addOne((handler, thread))
  }

  def removeHandler[T](handler: EventHandler[T]): Unit = {
    // Trying to remove a handler which has already been removed or hasn't been added
    // is not allowed (as per spesification).
    // However, re-removing a handler after it's been re-added is allowed (why not?).
    if (!(handlersAndThreads.contains(handler))) {
      throw new IllegalArgumentException("Given handler doesn't exist.") // Misuse of the API
    }
    
    // Remove the handler and the thread, and cancel the thread.
    val thread: WorkerThread[_] = handlersAndThreads.remove(handler) match {
      case Some(i) => i
      case None => throw new Exception("No thread corresponds to the given handler.") // Something went horribly wrong...
    }
    thread.cancelThread()
  }

  final class WorkerThread[T](private val handler: EventHandler[T]) extends Thread {

    override def run(): Unit = {
      // Run the loop for as long as the thread is not interuppted.
      while (!(Thread.interrupted)) {
        var dataIsNull: Boolean = false
        // Get data from the handle and enqueue it in the BlockingEventQueue events.
        try {
          val data: T = handler.getHandle.read() // Possibility of an interuppted exception.
          if (data == null) {
            dataIsNull = true
          }
          val event = new Event(data, handler) // Shouldn't throw any exceptions when used reasonably.
          events.enqueue(event) // Possibility of an interuppted exception.
        } catch {
            // InterruptedException is caused by the thread being interrupted midst reading or enqueueing.
            // Therefore, we can safely catch the exception and interrupt the thread (we want to stop the thread).
            case n: InterruptedException => this.interrupt()
        }

        // If the last data value inside the enqueued event was null, stop the thread (as per specification).
        try {
          if (dataIsNull) {
            throw new InterruptedException()
          }
        } catch {
          case n: InterruptedException => this.interrupt()
        }
        
      }

      // There's no need to clear any data structures since the user of the reactor will call removeHandler()
      // after the handler has read a null value from the handle.
      // In other cases, the thread has been (indirectly) interrupted by the removeHandler() method which
      // has already removed the corresponding entries.

      // Just in case:
      // if (handlersAndThreads.contains(handler)) {
      //   handlersAndThreads.remove(handler)
      // }
    }

    def cancelThread(): Unit = {
      this.interrupt()
    }

  }

}


