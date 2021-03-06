package tictactoe.actor.user

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import grizzled.slf4j.Logging
import tictactoe.actor.game.GameManagerActor
import tictactoe.actor.messages._
import tictactoe.actor.user.UserHandlerActor._
import tictactoe.actor.user.UserTokenManagerActor.{RequestUserHandlerForToken, TOKEN, UserHandlerIfPresent}
import tictactoe.model.User
import util.FunctionalHelper.ofTuple

import scala.collection.mutable
import scala.util.{Failure, Success}

/**
  */
class UserHandlerActor(user: User, token: TOKEN) extends Actor with Logging {

  private val socketCache: mutable.Set[ActorRef] = mutable.Set.empty[ActorRef]
  private val lobbyCache = mutable.Map.empty[TOKEN, UserElement]

  private val userManager = context.actorSelection(context.system / UserTokenManagerActor.NAME)
  private val lobby = context.actorSelection(context.system / LobbyActor.NAME)

  private var gameOpt: Option[ActorRef] = None
  private var opponentOpt: Option[(UserElement, ActorRef)] = None
  private var moves: List[PlayerMove] = Nil
  private var startedThisGame = false

  private val beingAskedBy = mutable.Map.empty[TOKEN, (ActorRef, NAME)]


  override def preStart(): Unit = {
    lobby ! LobbyActor.RegisterUserToken(token, user.name)
    lobby ! LobbyActor.GetAll()
  }


  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    lobby ! LobbyActor.UnRegisterUserToken(token, user.name)
  }

  def handleRegisterWebSocket(): Unit = {
    val s = sender()
    socketCache.add(s)
    beingAskedBy.foreach(ofTuple((t, p) => s ! BroadcastMessage(GameRequested.toJson(UserElement(p._2, t)))))
  }

  def handleUnRegisterWebSocket(): Unit = {
    socketCache - sender()
    //FIXME IF EMPTY CLEAR
  }

  def handleRequestStatus(): Unit = {
    sender() ! BroadcastMessage(UserStatusMSG.toJson(UserStatus(user.name, token, lobbyCache.values.toList)))
  }


  def handleGetAllReturn(list: List[UserElement]): Unit = {
    list.filterNot(_.token == this.token).foreach(e => lobbyCache.put(e.token, e))
  }

  def handleBroadcastRegisterUserToken(token: String, username: String): Unit = {
    if (token == this.token)
      return
    val elem = UserElement(username, token)
    lobbyCache.put(token, elem)
    if (gameOpt.isEmpty)
      broadcast(UserLoggedIn.toJson(elem))
  }

  def handleBroadcastUnRegisterUserToken(token: String, username: String): Unit = {
    if (token == this.token)
      return
    val elem = UserElement(username, token)
    lobbyCache.remove(token)
    if (gameOpt.isEmpty)
      broadcast(UserLoggedOut.toJson(elem))
  }

  def handleAskOtherPlayerForGame(user: UserElement): Unit = {

    userManager.?(RequestUserHandlerForToken(user.token))(Timeout(10, TimeUnit.SECONDS), self).mapTo[UserHandlerIfPresent]
      .onComplete {
        case Success(msg) =>
          msg.handlerOpt.foreach(value => {
            self ! AskOtherPlayerForGameFORWARD(user, value.handler)
          })
        case Failure(_) =>
      }(context.dispatcher)
  }


  def handleAskOtherPlayerForGame(user: UserElement, userActor: ActorRef): Unit = {
    val deny = AcceptOrDenyGameWithRef(UserElement(user.name, token), accept = None, self)
    beingAskedBy.values.foreach(_._1 ! deny)
    beingAskedBy.clear()

    opponentOpt match {
      case Some(_) =>
        info("already asking another player for a Game")

      case None =>
        opponentOpt = Some((user, userActor))
        userActor ! BeingAskedForGame(UserElement(user.name, token), self)
    }
  }


  def handleBeingAskedForGame(otherPlayer: UserElement, otherRef: ActorRef): Unit = {
    // im either inGame or asking myself
    if (opponentOpt.isDefined)
      otherRef ! AcceptOrDenyGameWithRef(UserElement(user.name, token), accept = None, self)
    else {
      beingAskedBy.put(otherPlayer.token, (otherRef, otherPlayer.name))
      broadcast(GameRequested.toJson(otherPlayer))
    }
  }

  def handleAcceptOrDenyGame(otherPlayer: UserElement, accept: Boolean): Unit = {
    if (opponentOpt.isDefined)
      return

    beingAskedBy.remove(otherPlayer.token) match {
      case None => //ignore
      case Some(other) =>
        val deny = AcceptOrDenyGameWithRef(UserElement(user.name, token), None, self)
        if (accept) {
          info(s"starting game with p0: ${other._2}, p1: ${user.name}")
          val gameRef: ActorRef = context.actorOf(GameManagerActor.props((token, self), (otherPlayer.token, other._1)))
          startedThisGame = true
          gameOpt = Some(gameRef)
          opponentOpt = Some((otherPlayer, other._1))
          other._1 ! deny.copy(accept = gameOpt)
          beingAskedBy.values.foreach(_._1 ! deny)
          beingAskedBy.clear()
          messageInGame()
        } else {
          other._1 ! deny
        }
    }
  }

  def handleAcceptOrDenyGameWithRef(otherPlayer: UserElement, accept: Option[ActorRef], otherRef: ActorRef): Unit = {
    opponentOpt match {
      case None =>
        info("accept/deny without having been asked")
        throw new IllegalStateException("got a reply from someone without asking")

      case Some((_, op)) =>
        val isAccept: Boolean =
          accept match {
            case Some(gameRef) =>
              if (otherRef != op)
                throw new IllegalStateException(s"got a reply from ${otherPlayer.name} without asking him")
              gameOpt = Some(gameRef)
              startedThisGame = false
              true
            case None =>
              opponentOpt = None
              false
          }
        broadcast(AskForGame.toJson(AcceptGame(otherPlayer.name, otherPlayer.token, isAccept)))
        if (isAccept) messageInGame()
    }
  }


  private def messageInGame(): Unit = {
    lobby ! LobbyActor.SetUserTokenInGame(this.token, inGame = true)
    broadcast(StartGame.toJson(EmptyMessage))
  }


  def handleAskGameStatus(): Unit = {
    if (gameOpt.isEmpty)
      return

    val yourTurn =
      moves.headOption match {
        case None => startedThisGame
        case Some(playerMove) => playerMove.pMove.startsWith("O")
      }
    sender() ! BroadcastMessage(GameStatus.toJson(GameStatus(moves.reverse, yourTurn)))
  }

  def handleAskGamePlayers(): Unit = {
    if (gameOpt.isDefined)
      sender() ! BroadcastMessage(GamePlayers.toJson(GamePlayers(me = UserElement(user.name, token), other = opponentOpt.get._1)))
  }

  def handleGameMove(move: Move): Unit = {
    gameOpt match {
      case None => //ignore
        warn("move has been called without a running game")
      case Some(game) => game ! GameManagerActor.DoMove(move.move)
    }
  }

  private def buildPlayerMove(token: String, move: String): PlayerMove = {
    val p = if (token == this.token) "M-" else "O-"
    PlayerMove(p + move)
  }

  def handleTokenMoved(token: String, move: String): Unit = {
    val pm = buildPlayerMove(token, move)
    moves = pm :: moves

    broadcast(PlayerMove.toJson(pm))
  }

  def handleTokenEndMove(token: String, move: String, tie: Boolean): Unit = {
    gameOpt = None
    opponentOpt = None
    moves = Nil
    startedThisGame = false
    broadcast(GameFinish.toJson(GameFinish(buildPlayerMove(token, move).pMove, tie)))

    messageReturnToIndex()
  }

  def handleInvalidMove(token: String, move: String): Unit = {
    warn(s"invalid move: token $token,  move: $move")
    //Ignore
  }

  private def messageReturnToIndex(): Unit = {
    lobby ! LobbyActor.SetUserTokenInGame(this.token, inGame = false)

    broadcast(ReturnToIndex.toJson(EmptyMessage))
  }


  def handleSendDirectMessage(directMessage: DirectMessage): Unit = {
    opponentOpt match {
      case None => //ignore
      case Some((_, ref)) => ref ! BroadCastDirectMessage(directMessage)
    }

  }

  def handleBroadCastDirectMessage(directMessage: DirectMessage): Unit = {
    broadcast(DirectMessageMSG.toJson(directMessage))
  }

  override def receive: Receive = {
    case RegisterWebSocket() => handleRegisterWebSocket()
    case UnRegisterWebSocket() => handleUnRegisterWebSocket()
    case RequestStatus() => handleRequestStatus()

    case LobbyActor.GetAllReturn(list: List[UserElement]) =>
      handleGetAllReturn(list: List[UserElement])
    case LobbyActor.BroadcastRegisterUserToken(token: String, username: String) =>
      handleBroadcastRegisterUserToken(token: String, username: String)
    case LobbyActor.BroadcastUnRegisterUserToken(token: String, username: String) =>
      handleBroadcastUnRegisterUserToken(token: String, username: String)

    case AskOtherPlayerForGame(user: UserElement) =>
      handleAskOtherPlayerForGame(user: UserElement)
    case AskOtherPlayerForGameFORWARD(user: UserElement, userActor: ActorRef) =>
      handleAskOtherPlayerForGame(user: UserElement, userActor: ActorRef)
    case BeingAskedForGame(otherPlayer: UserElement, otherRef: ActorRef) => handleBeingAskedForGame(otherPlayer: UserElement, otherRef: ActorRef)
    case AcceptOrDenyGame(otherPlayer: UserElement, accept: Boolean) =>
      handleAcceptOrDenyGame(otherPlayer: UserElement, accept: Boolean)
    case AcceptOrDenyGameWithRef(otherPlayer: UserElement, accept: Option[ActorRef], otherRef: ActorRef) =>
      handleAcceptOrDenyGameWithRef(otherPlayer: UserElement, accept: Option[ActorRef], otherRef: ActorRef)

    case AskGameStatus() => handleAskGameStatus()
    case AskGamePlayers() => handleAskGamePlayers()
    case GameMove(move: Move) => handleGameMove(move: Move)

    case GameManagerActor.TokenMoved(token: String, move: String) =>
      handleTokenMoved(token: String, move: String)
    case GameManagerActor.TokenEndMove(token: String, move: String, tie: Boolean) =>
      handleTokenEndMove(token: String, move: String, tie: Boolean)
    case GameManagerActor.InvalidMove(token: String, move: String) =>
      handleInvalidMove(token: String, move: String)

    case SendDirectMessage(directMessage: DirectMessage) =>
      handleSendDirectMessage(directMessage: DirectMessage)
    case BroadCastDirectMessage(directMessage: DirectMessage) =>
      handleBroadCastDirectMessage(directMessage: DirectMessage)

    case any => warn(s"illegal message + $any")
      throw new IllegalArgumentException("Invalid message")
  }

  def broadcast(message: String): Unit = {
    val out = BroadcastMessage(message)
    socketCache.foreach(_ ! out)
  }

}


object UserHandlerActor {
  private final type NAME = String


  case class RequestGameStatus()

  case class RequestGameStatusRet(either: Either[String, IllegalStateException])

  case class RequestStatus()

  case class RequestStatusRet(either: Either[String, IllegalStateException])

  case class BroadcastMessage(message: String)

  case class RegisterWebSocket()

  case class UnRegisterWebSocket()

  case class AskOtherPlayerForGame(user: UserElement)

  private case class AskOtherPlayerForGameFORWARD(user: UserElement, userActor: ActorRef)

  private case class BeingAskedForGame(otherPlayer: UserElement, otherRef: ActorRef)

  case class AcceptOrDenyGame(otherPlayer: UserElement, accept: Boolean)

  private case class AcceptOrDenyGameWithRef(otherPlayer: UserElement, accept: Option[ActorRef], otherRef: ActorRef)

  case class AskGameStatus()

  case class AskGamePlayers()

  case class GameMove(move: Move)


  case class SendDirectMessage(directMessage: DirectMessage)

  case class BroadCastDirectMessage(directMessage: DirectMessage)


  def props(user: User, token: String): Props = Props(new UserHandlerActor(user: User, token: String))
}