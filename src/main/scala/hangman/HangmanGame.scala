package hangman
import hangman.util.{AcceptHandle, TCPTextHandle}
import reactor.Dispatcher
import reactor.api.{EventHandler, Handle, Event}
import java.net.{Socket}
import scala.collection.mutable.HashSet

class HangmanGame(val hiddenWord: String, val initialnumberOfGuesses: Int) {

  private var dispatcher: Dispatcher = new Dispatcher()
  private var gameState = new GameState(hiddenWord, initialnumberOfGuesses, Set())
  private var acceptHandler = new AcceptHandler()

  // players keeps track of persons who have *joined* the game (and are still playing the game).
  private var players: HashSet[PersonHandler] = new HashSet()
  // allPersons keeps track of persons who are *connected* to the server.
  // allPersons is basically players + those who have connected but not typed their name yet.
  private var allPersons: HashSet[PersonHandler] = new HashSet()

  // Start accepting connections.
  def start() = {
    dispatcher.addHandler(acceptHandler)
    dispatcher.handleEvents()
  }

  class AcceptHandler() extends EventHandler[Socket] {
  
    private val handle = new AcceptHandle()

    def getHandle: Handle[Socket] = handle.asInstanceOf[Handle[Socket]]

    // Create a PersonHandler for the person connecting to the server.
    def handleEvent(socket: Socket): Unit = {
      socket match {
        case null => throw new Exception("Connecting to a new person failed.")
        case someSocket => {
          val playerHandler = new PersonHandler(someSocket)
          dispatcher.addHandler(playerHandler)
          System.out.println("New person connected with the socket: " + someSocket)
        }
      }
    }

    // Remove the AcceptHandler from dispatcher and close the related handle (and the server socket).
    // This method should only be called after closing all PersonHandlers.
    def close() {
      dispatcher.removeHandler(this)
      handle.close()
    }

  }

  // A PersonHandler instance exists for every person *connected* to the server.
  class PersonHandler(private val socket: Socket) extends EventHandler[String] {

    // Automatically add the created PersonHandler to allPersons to keep track of the instances.
    allPersons.add(this)

    private val handle = new TCPTextHandle(socket)
    private var name: String = "NoNameGivenYet"
    private var firstMessage = true
    
    def getHandle: Handle[String] = handle.asInstanceOf[Handle[String]]

    def handleEvent(data: String): Unit = {
      data match {
        // If the received data is null, close and remove the PersonHandler from data structures.
        // (as per specification)
        case null => {
          close()
          // System.out.println("Connection to " + name + " is removed.")
        }
        case someData => {
          // Process the data as a name.
          if (firstMessage) {
            name = someData
            require(name.length() >= 1) // As per specification
            require(!name.contains(" ")) // As per specification
            firstMessage = false
            send(gameState.getMaskedWord + " " + gameState.numberOfGuesses) // As per specification
            // Now the person has given their name, and a response has been sent back.
            // Therefore, the person has joined the game, and they should be both a player and a person.
            players.add(this) 
          }
          // Process the data as a guessed character.
          else {
            require(someData.length() == 1) // As per specification
            val guess: Char = someData.charAt(0)
            // Make a guess and send a response to all players.
            gameState = gameState.performGuess(guess)
            sendToAllPlayers(guess + " " + gameState.getMaskedWord + " " + gameState.numberOfGuesses + " " + name)

            // If the game has ended, first shut down all PersonHandlers (and individual connections).
            // Finally, shut down the acceptHandler.
            if (gameState.gameOverChecker) {
              closeAllPersonHandlers()
              acceptHandler.close()
              // The server program should stop.
            }
          }
          // System.out.println(name + " sent a message: " + someData)
        }
      }
    }

    // Send a message to every player.
    def sendToAllPlayers(s: String): Unit = {
      for (player: PersonHandler <- players) {
        player.send(s)
      }
    }

    // Send a message to the person corresponding to this PersonHandler.
    def send(s: String): Unit = {
      handle.write(s)
    }

    // Close and remove every PersonHandler (and the corresponding connection).
    def closeAllPersonHandlers(): Unit = {
      for (person: PersonHandler <- allPersons) {
        person.close()
      }
    }

    // Close and remove this PersonHandler (and the corresponding connection).
    def close(): Unit = {
      dispatcher.removeHandler(this)
      players.remove(this)
      allPersons.remove(this)
      handle.close()
    } 

  }

}

object HangmanGame {

  def main(args: Array[String]): Unit = {
    // Exctract data from the command line arguments.
    require(args.size == 2) // As per specification
    val word: String = args(0)
    require(word.matches("[a-z]+")) // As per specification + can't have a "word" length 0
    val allowedFailedGuessess: Int = args(1).toInt
    require(allowedFailedGuessess > 0) // As per specification
    
    // Start the game.
    var hg: HangmanGame = new HangmanGame(word, allowedFailedGuessess)
    hg.start()
  }

}
