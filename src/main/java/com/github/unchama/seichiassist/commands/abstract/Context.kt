package com.github.unchama.seichiassist.commands.abstract

import arrow.core.*
import arrow.core.extensions.either.monad.binding
import arrow.syntax.collections.firstOption
import com.github.unchama.seichiassist.commands.abstract.ArgTransFailureCause.NOT_ENOUGH_ARG
import com.github.unchama.seichiassist.commands.abstract.ArgTransFailureCause.TRANSFORM_FAILED
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

/**
 * コマンドの実行時に使用された[Command]とエイリアスの情報
 */
data class ExecutedCommand(val command: Command, val aliasUsed: String)

/**
 * コマンドの実行に関わる一連の生パラメータの情報
 */
data class RawCommandContext(val sender: CommandSender,
                             val command: ExecutedCommand,
                             val args: List<String>)

/**
 * 変換されたコマンド引数の情報
 *
 * @param parsed コマンド引数のうち, [Any?]を型上限とするオブジェクトに変換されたもの.
 * @param yetToBeParsed コマンド引数のうち, [parsed]へと変換されていない文字列.
 */
data class PartiallyParsedArgs(val parsed: List<Any?>, val yetToBeParsed: List<String>) {
    /**
     * [yetToBeParsed]の先頭にある文字列を[parser]で変換した値を[parsed]に取る新しい[ParsedArgCommandContext]を計算する.
     * 引数が不足していたり, 変換に失敗していた場合[ArgTransFailureCause]を[Either.left]経由で返す.
     *
     * @param parser 変換に失敗したとき[None]を, そうでなければ成功値を含んだ[Option]を返す関数
     */
    fun <R> parseArgHead(parser: (String) -> Option<R>): Either<ArgTransFailureCause, PartiallyParsedArgs> =
            binding {
                val (nonParsedArgHead) = yetToBeParsed.firstOption().toEither { NOT_ENOUGH_ARG }
                val (transformedArgHead) = parser(nonParsedArgHead).toEither { TRANSFORM_FAILED }

                this@PartiallyParsedArgs.copy(
                        parsed = parsed.plusElement(transformedArgHead),
                        yetToBeParsed = yetToBeParsed.drop(1)
                )
            }
}

/**
 * コマンドの実行時のコマンド引数や実行者などの情報を変換, 加工したデータ.
 *
 * @param CS [CommandSender]オブジェクトの型上限. [sender]は[CS]であることまでが保証されている.
 * @param command 実行コマンドに関する情報
 * @param args 引数情報
 */
data class ParsedArgCommandContext<out CS: CommandSender>(val sender: CS,
                                                          val command: ExecutedCommand,
                                                          val args: PartiallyParsedArgs)
