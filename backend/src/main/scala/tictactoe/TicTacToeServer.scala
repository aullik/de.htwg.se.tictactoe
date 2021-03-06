package tictactoe

import java.util.Locale
import javax.inject.Singleton

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.Inject
import tictactoe.actor.user.{LobbyActor, UserTokenManagerActor}
import tictactoe.persistence.{Persistence, PersistenceEnvironment}

@Singleton
class TicTacToeServer @Inject() private(system: ActorSystem, environment: PersistenceEnvironment) {

  Locale.setDefault(Locale.GERMAN)

  final val lobbyActor: ActorRef = system.actorOf(LobbyActor.props, LobbyActor.NAME)
  final val userTokenManagerActor: ActorRef = system.actorOf(UserTokenManagerActor.props, UserTokenManagerActor.NAME)

  final val persistence: Persistence = environment.persistence

}



