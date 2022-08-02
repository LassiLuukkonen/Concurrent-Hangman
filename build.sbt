name := "CS-E4110-assignment"

version := "21.11.14"

scalaVersion := "2.13.6"

libraryDependencies += "org.scalatest" %% "scalatest-funsuite" % "3.2.0"

// Define the main method
Compile / run / mainClass := Some("hangman.HangmanGame")
