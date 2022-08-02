package hangman
import hangman.util.{AcceptHandle, TCPTextHandle}
import reactor.Dispatcher
import reactor.api.{EventHandler, Handle, Event}
import java.net.{Socket}
import scala.collection.mutable.HashSet
import scala.util.Random
import scala.io.Source

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

    send("Please give your NAME.")
    
    def getHandle: Handle[String] = handle.asInstanceOf[Handle[String]]

    def handleEvent(data: String): Unit = {
      data match {
        // If the received data is null, close and remove the PersonHandler from data structures.
        // (as per specification)
        case null => {
          close()
          System.out.println("Connection to " + name + " is removed.")
        }
        case someData => {
          // Process the data as a name.
          if (firstMessage) {
            if (someData.length() < 2 || someData.contains(" ") || !someData.matches("^[a-zA-Z0-9]*$")) {
              send("Please give ANOTHER NAME. The name must consist only of alphanumeric characters and it has to be at least 2 charecter long.")
              System.out.println(name + " sent a non-acceptable name: " + someData)
            }
            else {
              name = someData
              firstMessage = false
              send("Thanks for giving your name, " + name + ".")
              send("The word is " + gameState.getMaskedWord + ". You have " + gameState.numberOfGuesses + " guess(es) left.") // As per specification
              // Now the person has given their name, and a response has been sent back.
              // Therefore, the person has joined the game, and they should be both a player and a person.
              players.add(this)
              System.out.println(name + " sent an acceptable name: " + someData)
            }
            
          }
          // Process the data as a guessed character.
          else {
            if (!someData.matches("[a-z]")) {
              send("Please give ANOTHER GUESS. A guess must be one lowercase letter of the English alphabet.")
              System.out.println(name + " sent a non-acceptable guess: " + someData)
            }
            else {
              val guess: Char = someData.charAt(0)
              // Make a guess and send a response to all players.
              gameState = gameState.performGuess(guess)
              sendToAllPlayers("The word is " + gameState.getMaskedWord + ". " + name + " made the guess " + guess +  ". You have " + gameState.numberOfGuesses + " guess(es) left.")
              
              System.out.println(name + " sent an acceptable guess: " + someData)
              // If the game has ended, first shut down all PersonHandlers (and individual connections).
              // Finally, shut down the acceptHandler.
              if (gameState.gameOverChecker) {
                System.out.println("Game is over.")
                sendToAllPlayers("")
                if (gameState.gameWonChecker) { sendToAllPlayers("YOU WIN!") }
                if (gameState.gameLostChecker) { sendToAllPlayers("YOU LOSE!") }
                sendToAllPlayers("The word was " + gameState.word + ".")
                closeAllPersonHandlers()
                acceptHandler.close()
                // The server program should stop.
              }
            }
          }
          
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
    val words = Source.fromFile("words.txt").getLines.toList
    val random = new Random
    val word = words(random.nextInt(words.length))
    val allowedFailedGuessess: Int = (3).max(word.length/2)

    // Start the game.
    var hg: HangmanGame = new HangmanGame(word, allowedFailedGuessess)
    hg.start()
  }

}
