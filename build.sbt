name := "cats-effect-tutorial"

val ceVersion = "3.5.1"

version := ceVersion

scalaVersion := "2.13.11"

libraryDependencies += "org.typelevel" %% "cats-effect" % ceVersion withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps"
)
