package example.org.nirmalya.experiments

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.pattern._
import akka.util.Timeout
import example.org.nirmalya.experiments.GameSessionHandlingServiceProtocol.{ExternalAPIParams, GameEndedByPlayer, GameSession, HuddleGame, QuestionAnswerTuple, RecordingStatus}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

case object Fire
case object ShutYourself

/**
  * Created by nirmalya on 20/6/17.
  */
class GameSessionSPOCActor extends Actor with ActorLogging {


  implicit val executionContext = context.dispatcher
  implicit val askTimeOutDuration:Timeout = Duration(3, "seconds")

  var activeGameSessionActors: Map[GameSession, ActorRef] = Map.empty

  def receive = {

    case r: ExternalAPIParams.REQStartAGameWith =>
      val gameSession = GameSession(r.toString, "Ignore")

      if (activeGameSessionActors.isDefinedAt(gameSession))
        sender ! RecordingStatus(s"GameSession with $r is already active.")
      else {
        val originalSender = sender()
        val child = context.system.actorOf(GamePlayRecorderActor(true, gameSession), gameSession.toString)
        context.watch(child)

        activeGameSessionActors = activeGameSessionActors + Tuple2(gameSession,child)


        val confirmation = (child ? HuddleGame.EvStarted(System.currentTimeMillis(), gameSession)).mapTo[RecordingStatus]
        confirmation.onComplete {
          case Success(d) =>   originalSender ! d
          case Failure(e) =>   originalSender ! RecordingStatus(e.getMessage)
        }
      }

    case r: ExternalAPIParams.REQPlayAGameWith =>
      val gameSession = GameSession(r.sessionID, "Ignore")

      val originalSender = sender()
      activeGameSessionActors.get(gameSession) match {

        case Some (sessionActor) =>

          val confirmation =
            (sessionActor ? HuddleGame.EvQuestionAnswered(
                                          System.currentTimeMillis(),
                                          QuestionAnswerTuple(r.questionID.toInt,r.answerID.toInt,r.isCorrect, r.score),
                                          gameSession
                                       )
            ).mapTo[RecordingStatus]
          confirmation.onComplete {
            case Success(d) =>   originalSender ! d
            case Failure(e) =>   originalSender ! RecordingStatus(e.getMessage)
          }
        case None                =>
          originalSender ! RecordingStatus(s"No session with ${r.sessionID} exists.")
      }

    case r: ExternalAPIParams.REQPauseAGameWith =>
      val gameSession = GameSession(r.sessionID, "Ignore")

      val originalSender = sender()
      activeGameSessionActors.get(gameSession) match {

        case Some (sessionActor) =>

          val confirmation = (sessionActor ? HuddleGame.EvPaused(System.currentTimeMillis(), gameSession)).mapTo[RecordingStatus]
          confirmation.onComplete {
            case Success(d) =>   originalSender ! d
            case Failure(e) =>   originalSender ! RecordingStatus(e.getMessage)
          }
        case None                =>
          originalSender ! RecordingStatus(s"No session with ${r.sessionID} exists.")
      }

    case r: ExternalAPIParams.REQEndAGameWith =>

      val gameSession = GameSession(r.sessionID, "Ignore")

      val originalSender = sender()
      activeGameSessionActors.get(gameSession) match {

        case Some (sessionActor) =>

          val confirmation = (sessionActor ? HuddleGame.EvEnded(
                                                System.currentTimeMillis(),
                                                GameEndedByPlayer,
                                                gameSession)
            ).mapTo[RecordingStatus]
          confirmation.onComplete {
            case Success(d) =>   originalSender ! d
            case Failure(e) =>   originalSender ! RecordingStatus(e.getMessage)
          }
        case None                =>
          originalSender ! RecordingStatus(s"No session with ${r.sessionID} exists.")
      }

    // TODO: Revisit the following handler. What is the best way to remember the session that the this
    // TODO: this terminated actor has been seeded with?
    case Terminated(sessionActor: GamePlayRecorderActor) =>

      activeGameSessionActors = activeGameSessionActors - sessionActor.seededWithSession
      log.info("Session Actor ($a) terminated." )


    case (ShutYourself) =>
      context stop(self)

    case (m: Any) =>

      println("Unknown message = [" + m + "] received!")
//      context stop(self)
  }

}

object GameSessionSPOCActor {
  def props = Props(new GameSessionSPOCActor)
}