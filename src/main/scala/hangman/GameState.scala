package hangman

case class GameState(word: String, numberOfGuesses: Int, guessedChars: Set[Char]) {
  require(word != null && word.length > 0)
  require(numberOfGuesses >= 0)
  require(guessedChars != null)

  // checks if the game has been lost already
  def gameLostChecker: Boolean = {
    numberOfGuesses <= 0
  }

  // checks if the game has been won already
  def gameWonChecker: Boolean = {
    word.forall(c => guessedChars.contains(c))
  }

  // checks if the game is over already
  def gameOverChecker: Boolean = {
    gameLostChecker || gameWonChecker
  }

  // returns the word that the player is trying to guess, BUT the
  // charecters that haven't yet been guessed right, are hidden
  def getMaskedWord: String = {
    word.map{ c =>
      guessedChars.contains(c) match {
        case true => c
        case false => '_'
      }
    }
  }

  // returns a modified game state according to the player's guess
  def performGuess(guess: Char): GameState = {
    word.contains(guess) match {
      case false => GameState(word, numberOfGuesses - 1, guessedChars + guess)
      case true => GameState(word, numberOfGuesses, guessedChars + guess)
    }
  }

}
