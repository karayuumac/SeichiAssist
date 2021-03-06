package com.github.unchama.seichiassist.mebius.bukkit.repository

import cats.effect.{Effect, IO}
import com.github.unchama.playerdatarepository.JoinToQuitPlayerDataRepository
import com.github.unchama.seichiassist.mebius.domain.speech.{MebiusSpeechBlockageState, MebiusSpeechGateway}
import com.github.unchama.seichiassist.mebius.service.MebiusSpeechService
import org.bukkit.entity.Player

class SpeechServiceRepository[F[_] : Effect](implicit
                                             getFreshBlockageState: F[MebiusSpeechBlockageState[F]],
                                             gatewayProvider: Player => MebiusSpeechGateway[F])
  extends JoinToQuitPlayerDataRepository[MebiusSpeechService[F]] {

  override protected def initialValue(player: Player): MebiusSpeechService[F] = {
    val freshBlockingState = Effect[F].toIO(getFreshBlockageState).unsafeRunSync()
    new MebiusSpeechService[F](gatewayProvider(player), freshBlockingState)
  }

  override protected def unloadData(player: Player, r: MebiusSpeechService[F]): IO[Unit] = IO.unit
}
