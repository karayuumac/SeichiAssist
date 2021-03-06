package com.github.unchama.seichiassist.infrastructure.migration.targets

import cats.effect.IO
import com.github.unchama.itemmigration.targets.WorldLevelData
import com.github.unchama.seichiassist.SeichiAssist
import com.github.unchama.util.external.{ExternalPlugins, ExternalServices}
import org.bukkit.World

private object DelegatedImpls {
  val getWorlds: IO[IndexedSeq[World]] = {
    val multiverseCore = ExternalPlugins.getMultiverseCore

    import scala.jdk.CollectionConverters._

    IO {
      multiverseCore.getMVWorldManager
        .getMVWorlds.asScala
        .map(_.getCBWorld).toIndexedSeq
    }
  }

  val getWorldChunkCoordinates: World => IO[Seq[(Int, Int)]] =
    ExternalServices.getChunkCoordinates(SeichiAssist.seichiAssistConfig.chunkSearchCommandBase())
}

object SeichiAssistWorldLevelData extends WorldLevelData(DelegatedImpls.getWorlds, DelegatedImpls.getWorldChunkCoordinates)
