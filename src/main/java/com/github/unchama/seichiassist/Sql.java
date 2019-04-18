package com.github.unchama.seichiassist;

import com.github.unchama.seichiassist.data.GachaData;
import com.github.unchama.seichiassist.data.MineStackGachaData;
import com.github.unchama.seichiassist.data.PlayerData;
import com.github.unchama.seichiassist.data.RankData;
import com.github.unchama.seichiassist.database.init.DonateDataTableQueryGenerator;
import com.github.unchama.seichiassist.database.init.GachaDataTableQueryGenerator;
import com.github.unchama.seichiassist.database.init.MineStackGachaDataTableQueryGenerator;
import com.github.unchama.seichiassist.database.init.PlayerDataTableQueryGenerator;
import com.github.unchama.seichiassist.task.CheckAlreadyExistPlayerDataTaskRunnable;
import com.github.unchama.seichiassist.task.CoolDownTaskRunnable;
import com.github.unchama.seichiassist.task.PlayerDataSaveTaskRunnable;
import com.github.unchama.seichiassist.util.BukkitSerialization;
import com.github.unchama.seichiassist.util.Util;
import com.github.unchama.util.ActionStatus;
import com.github.unchama.util.Try;
import com.github.unchama.util.ValuelessTry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.*;
import java.util.function.Function;

import static com.github.unchama.util.ActionStatus.Fail;
import static com.github.unchama.util.ActionStatus.Ok;

//MySQL操作関数
//TODO: 直接SQLに変数を連結しているが、順次PreparedStatementに置き換えていきたい
public class Sql{
	private @NotNull final String url;
	private @NotNull final String databaseName;
	private @NotNull final String id;
	private @NotNull final String pw;
	public Connection con = null;
	private Statement stmt = null;

	private SeichiAssist plugin = SeichiAssist.instance;
	private static Config config = SeichiAssist.config;

	//コンストラクタ
	Sql(@NotNull String url, @NotNull String databaseName, @NotNull String id, @NotNull String pw){
		this.url = url;
		this.databaseName = databaseName;
		this.id = id;
		this.pw = pw;
	}

	/**
	 * 接続関数
	 */
	public ActionStatus connectAndInitializeDatabase() {
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
			e.printStackTrace();
			plugin.getLogger().info("Mysqlドライバーのインスタンス生成に失敗しました");
			return Fail;
		}

		final Function<String, String> errorMessageForTable = (tableName) -> tableName + "テーブル作成に失敗しました";

		final Try<String> tryResult =
				Try.begin("SQL接続に失敗しました", this::establishMySQLConnection)
				.ifOkThen("データベース作成に失敗しました", this::createDB)
				.ifOkThen(errorMessageForTable.apply("gachadata"), this::createGachaDataTable)
				.ifOkThen(errorMessageForTable.apply("MineStack用gachadata"), this::createMineStackGachaDataTable)
				.ifOkThen(errorMessageForTable.apply("donatedata"), this::createDonateDataTable)
				.ifOkThen(errorMessageForTable.apply("playerdata"), this::createPlayerDataTable);

		return tryResult.mapFailValue(Ok, failedMessage -> { plugin.getLogger().info(failedMessage); return Fail; });
	}

	private ActionStatus establishMySQLConnection(){
		try {
			if(stmt != null && !stmt.isClosed()){
				stmt.close();
				con.close();
			}
			con = DriverManager.getConnection(url, id, pw);
			stmt = con.createStatement();
			return Ok;
		} catch (SQLException e) {
			e.printStackTrace();
			return Fail;
		}
	}

	/**
	 * 接続正常ならOk、そうでなければ再接続試行後正常でOk、だめならFailを返す
	 */
	// TODO このメソッドの戻り値はどこにも使われていない。異常系はその状態を引きずらずに処理を止めるべき
	public ActionStatus ensureConnection(){
		try {
			if(con.isClosed()){
				plugin.getLogger().warning("sqlConnectionクローズを検出。再接続試行");
				con = DriverManager.getConnection(url, id, pw);
			}
			if(stmt.isClosed()){
				plugin.getLogger().warning("sqlStatementクローズを検出。再接続試行");
				stmt = con.createStatement();
			}
		} catch (SQLException e) {
			e.printStackTrace();
			//イクセプションった時に接続再試行
			plugin.getLogger().warning("sqlExceptionを検出。再接続試行");
			if(establishMySQLConnection() == Ok){
				plugin.getLogger().info("sqlコネクション正常");
				return Ok;
			}else{
				plugin.getLogger().warning("sqlコネクション不良を検出");
				return Fail;
			}
		}

		return Ok;
	}

	/**
	 * コネクション切断処理
	 *
	 * @return 成否
	 */
	public ActionStatus disconnect(){
		if (con != null) {
			try {
				stmt.close();
				con.close();
			} catch (SQLException e) {
				e.printStackTrace();
				return Fail;
			}
		}
		return Ok;
	}

	/**
	 * コマンド実行関数
	 * @param command コマンド内容
	 * @return 成否
	 */
	// private変更禁止！(処理がバッティングします)
	private ActionStatus executeCommand(String command) {
		ensureConnection();
		try {
			stmt.executeUpdate(command);
			return Ok;
		}catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.getMessage();
			e.printStackTrace();
			return Fail;
		}
	}

	/**
	 * データベース作成
	 *
	 * @return 成否
	 */
	private ActionStatus createDB(){
		String command = "CREATE DATABASE IF NOT EXISTS " + databaseName
				+ " character set utf8 collate utf8_general_ci";
		return executeCommand(command);
	}

	/**
	 * playerdataテーブルの作成及び初期化を行うメソッド。
	 *
	 * @return 成否
	 */
	private ActionStatus createPlayerDataTable(){
		final String tableName = SeichiAssist.PLAYERDATA_TABLENAME;
		final String tableReference = databaseName + "." + tableName;

		final PlayerDataTableQueryGenerator queryGenerator =
				new PlayerDataTableQueryGenerator(tableReference, config);

        return ValuelessTry
				.begin(() -> executeCommand(queryGenerator.generateCreateQuery()))
				.ifOkThen(() -> executeCommand(queryGenerator.generateAdditionalColumnAlterQuery()))
				.overallStatus();
	}

	/**
	 * gachadataテーブルの作成及び初期化を行うメソッド。
	 *
	 * @return 成否
	 */
	private ActionStatus createGachaDataTable() {
		final String tableName = SeichiAssist.GACHADATA_TABLENAME;
		final String tableReference = databaseName + "." + tableName;

		final GachaDataTableQueryGenerator queryGenerator =
				new GachaDataTableQueryGenerator(tableReference);

		return ValuelessTry
				.begin(() -> executeCommand(queryGenerator.generateCreateQuery()))
				.ifOkThen(() -> executeCommand(queryGenerator.generateAdditionalColumnAlterQuery()))
				.overallStatus();
	}

	/**
	 * minestackテーブルの作成及び初期化を行うメソッド。
	 *
	 * @return 成否
	 */
	private ActionStatus createMineStackGachaDataTable(){
		final String tableName = SeichiAssist.MINESTACK_GACHADATA_TABLENAME;
        final String tableReference = databaseName + "." + tableName;

        final MineStackGachaDataTableQueryGenerator queryGenerator =
				new MineStackGachaDataTableQueryGenerator(tableReference);

        return ValuelessTry
				.begin(() -> executeCommand(queryGenerator.generateCreateQuery()))
				.ifOkThen(() -> executeCommand(queryGenerator.generateAdditionalColumnAlterQuery()))
				.overallStatus();
	}

	private ActionStatus createDonateDataTable() {
		final String tableName = SeichiAssist.DONATEDATA_TABLENAME;
        final String tableReference = databaseName + "." + tableName;

        final DonateDataTableQueryGenerator queryGenerator =
				new DonateDataTableQueryGenerator(tableReference);

        return ValuelessTry
				.begin(() -> executeCommand(queryGenerator.generateCreateQuery()))
				.ifOkThen(() -> executeCommand(queryGenerator.generateAdditionalColumnAlterQuery()))
		        .overallStatus();
	}

	//投票特典配布時の処理(p_givenvoteの値の更新もココ)
	public int compareVotePoint(Player player,final PlayerData playerdata){

		if(!playerdata.votecooldownflag){
			player.sendMessage(ChatColor.RED + "しばらく待ってからやり直してください");
			return 0;
		}else{
			//連打による負荷防止の為クールダウン処理
			new CoolDownTaskRunnable(player,true,false,false).runTaskLater(plugin,1200);
		}
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = playerdata.uuid.toString();
		int p_vote = 0;
		int p_givenvote = 0;
		String command = "select p_vote,p_givenvote from " + databaseName + "."  + table
				+ " where uuid = '" + struuid + "'";
		try (ResultSet lrs = stmt.executeQuery(command)) {
			while (lrs.next()) {
				p_vote = lrs.getInt("p_vote");
				p_givenvote = lrs.getInt("p_givenvote");
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			player.sendMessage(ChatColor.RED + "投票特典の受け取りに失敗しました");
			return 0;
		}
		//比較して差があればその差の値を返す(同時にp_givenvoteも更新しておく)
		if(p_vote > p_givenvote){
			command = "update " + databaseName + "." + table
					+ " set p_givenvote = " + p_vote
					+ " where uuid like '" + struuid + "'";
			if(executeCommand(command) == Fail){
				player.sendMessage(ChatColor.RED + "投票特典の受け取りに失敗しました");
				return 0;
			}

			return p_vote - p_givenvote;
		}
		player.sendMessage(ChatColor.YELLOW + "投票特典は全て受け取り済みのようです");
		return 0;

	}

	//最新のnumofsorryforbug値を返してmysqlのnumofsorrybug値を初期化する処理
	public int givePlayerBug(Player player,final PlayerData playerdata){
		if(!playerdata.votecooldownflag){
			player.sendMessage(ChatColor.RED + "しばらく待ってからやり直してください");
			return 0;
		}else{
			//連打による負荷防止の為クールダウン処理
			new CoolDownTaskRunnable(player,true,false,false).runTaskLater(plugin,1200);
		}
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = playerdata.uuid.toString();
		int numofsorryforbug = 0;
		String command = "select numofsorryforbug from " + databaseName + "." + table
				+ " where uuid = '" + struuid + "'";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				numofsorryforbug = lrs.getInt("numofsorryforbug");
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			player.sendMessage(ChatColor.RED + "ガチャ券の受け取りに失敗しました");
			return 0;
		}
		//576より多い場合はその値を返す(同時にnumofsorryforbugから-576)
		if(numofsorryforbug > 576) {
			command = "update " + databaseName + "." + table
					+ " set numofsorryforbug = numofsorryforbug - 576"
					+ " where uuid like '" + struuid + "'";
			if(executeCommand(command) == Fail){
				player.sendMessage(ChatColor.RED + "ガチャ券の受け取りに失敗しました");
				return 0;
			}

			return 576;
		}//0より多い場合はその値を返す(同時にnumofsorryforbug初期化)
		else if(numofsorryforbug > 0){
			command = "update " + databaseName + "." + table
					+ " set numofsorryforbug = 0"
					+ " where uuid like '" + struuid + "'";
			if (executeCommand(command) == Fail) {
				player.sendMessage(ChatColor.RED + "ガチャ券の受け取りに失敗しました");
				return 0;
			}

			return numofsorryforbug;
		}
		player.sendMessage(ChatColor.YELLOW + "ガチャ券は全て受け取り済みのようです");
		return 0;

	}

	//投票時にmysqlに投票ポイントを加算しておく処理
	public ActionStatus addVotePoint(String name) {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String command;

		command = "update " + databaseName + "." + table
				+ " set p_vote = p_vote + 1" //1加算
				+ " where name like '" + name + "'";

		return executeCommand(command);

	}

	//プレミアムエフェクトポイントを加算しておく処理
	public ActionStatus addPremiumEffectPoint(String name, int num) {
		final String table = SeichiAssist.PLAYERDATA_TABLENAME;
		final String command = "update " + databaseName + "." + table
				+ " set premiumeffectpoint = premiumeffectpoint + " + num //引数で来たポイント数分加算
				+ " where name like '" + name + "'";

		return executeCommand(command);
	}


	//指定されたプレイヤーにガチャ券を送信する
	public ActionStatus addPlayerBug(String name, int num) {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String command = "update " + databaseName + "." + table
				+ " set numofsorryforbug = numofsorryforbug + " + num
				+ " where name like '" + name + "'";

		return executeCommand(command);
	}

	public void loadPlayerData(PlayerData playerdata) {
		Player player = Bukkit.getPlayer(playerdata.uuid);
		player.sendMessage(ChatColor.YELLOW + "プレイヤーデータ取得中。完了まで動かずお待ち下さい…");
		new CheckAlreadyExistPlayerDataTaskRunnable(playerdata).runTaskAsynchronously(plugin);
	}

	//ondisable"以外"の時のプレイヤーデータセーブ処理(loginflag折りません)
	public void savePlayerData(PlayerData playerdata){
		new PlayerDataSaveTaskRunnable(playerdata,false,false).runTaskAsynchronously(plugin);
	}

	//ondisable"以外"の時のプレイヤーデータセーブ処理(ログアウト時に使用、loginflag折ります)
	public void saveQuitPlayerData(PlayerData playerdata) {
		new PlayerDataSaveTaskRunnable(playerdata,false,true).runTaskAsynchronously(plugin);
	}

	//ガチャデータロード
	public boolean loadGachaData(){
		String table = SeichiAssist.GACHADATA_TABLENAME;
		List<GachaData> gachadatalist = new ArrayList<>();
		//SELECT `totalbreaknum` FROM `playerdata` WHERE 1 ORDER BY `playerdata`.`totalbreaknum` DESC
		String command = "select * from " + databaseName + "." + table;
		try (ResultSet lrs = stmt.executeQuery(command)) {
			while (lrs.next()) {
				GachaData gachadata = new GachaData();
				Inventory inventory = BukkitSerialization.fromBase64(lrs.getString("itemstack"));
				gachadata.itemstack = (inventory.getItem(0));
				gachadata.amount = lrs.getInt("amount");
				gachadata.probability = lrs.getDouble("probability");
				gachadatalist.add(gachadata);
			}
		} catch (SQLException | IOException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		SeichiAssist.gachadatalist.clear();
		SeichiAssist.gachadatalist.addAll(gachadatalist);
		return true;

	}

	//MineStack用ガチャデータロード
	public boolean loadMineStackGachaData(){
		String table = SeichiAssist.MINESTACK_GACHADATA_TABLENAME;
		List<MineStackGachaData> gachadatalist = new ArrayList<>();
		//SELECT `totalbreaknum` FROM `playerdata` WHERE 1 ORDER BY `playerdata`.`totalbreaknum` DESC
		String command = "select * from " + databaseName + "." + table;
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				MineStackGachaData gachadata = new MineStackGachaData();
				Inventory inventory = BukkitSerialization.fromBase64(lrs.getString("itemstack"));
				gachadata.itemstack = (inventory.getItem(0));
				gachadata.amount = lrs.getInt("amount");
				gachadata.level = lrs.getInt("level");
				gachadata.obj_name = lrs.getString("obj_name");
				gachadata.probability = lrs.getDouble("probability");
				gachadatalist.add(gachadata);
			}
		} catch (SQLException | IOException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		SeichiAssist.msgachadatalist.clear();
		SeichiAssist.msgachadatalist.addAll(gachadatalist);
		return true;

	}

	//ガチャデータセーブ
	public boolean saveGachaData(){
		String table = SeichiAssist.GACHADATA_TABLENAME;

		//まずmysqlのガチャテーブルを初期化(中身全削除)
		String command = "truncate table " + databaseName + "." + table;
		if(executeCommand(command) == Fail){
			return false;
		}

		//次に現在のgachadatalistでmysqlを更新
		for(GachaData gachadata : SeichiAssist.gachadatalist){
			//Inventory作ってガチャのitemstackに突っ込む
			Inventory inventory = SeichiAssist.instance.getServer().createInventory(null, 9*1);
			inventory.setItem(0,gachadata.itemstack);

			command = "insert into " + databaseName + "." + table + " (probability,amount,itemstack)"
					+ " values"
					+ "(" + gachadata.probability
					+ "," + gachadata.amount
					+ ",'" + BukkitSerialization.toBase64(inventory) + "'"
					+ ")";
			if(executeCommand(command) == Fail){
				return false;
			}
		}
		return true;
	}

	//MineStack用ガチャデータセーブ
	public boolean saveMineStackGachaData(){
		String table = SeichiAssist.MINESTACK_GACHADATA_TABLENAME;

		//まずmysqlのガチャテーブルを初期化(中身全削除)
		String command = "truncate table " + databaseName + "." + table;
		if(executeCommand(command) == Fail){
			return false;
		}

		//次に現在のgachadatalistでmysqlを更新
		for(MineStackGachaData gachadata : SeichiAssist.msgachadatalist){
			//Inventory作ってガチャのitemstackに突っ込む
			Inventory inventory = SeichiAssist.instance.getServer().createInventory(null, 9*1);
			inventory.setItem(0,gachadata.itemstack);

			command = "insert into " + databaseName + "." + table + " (probability,amount,level,obj_name,itemstack)"
					+ " values"
					+ "(" + gachadata.probability
					+ "," + gachadata.amount
					+ "," + gachadata.level
					+ ",'" + gachadata.obj_name + "'"
					+ ",'" + BukkitSerialization.toBase64(inventory) + "'"
					+ ")";

			if (executeCommand(command) == Fail) {
				return false;
			}
		}
		return true;
	}

	//ランキング表示用に総破壊ブロック数のカラムだけ全員分引っ張る
	public boolean setRanking() {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		List<RankData> ranklist = SeichiAssist.ranklist;
		ranklist.clear();
		SeichiAssist.allplayerbreakblockint = 0;
		String command = "select name,level,totalbreaknum from " + databaseName + "." + table
				+ " order by totalbreaknum desc";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				RankData rankdata = new RankData();
				rankdata.name = lrs.getString("name");
				rankdata.level = lrs.getInt("level");
				rankdata.totalbreaknum = lrs.getLong("totalbreaknum");
				ranklist.add(rankdata);
				SeichiAssist.allplayerbreakblockint += rankdata.totalbreaknum;
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	//ランキング表示用にプレイ時間のカラムだけ全員分引っ張る
	public boolean setRanking_playtick() {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		List<RankData> ranklist = SeichiAssist.ranklist_playtick;
		ranklist.clear();
		String command = "select name,playtick from " + databaseName + "." + table
				+ " order by playtick desc";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				RankData rankdata = new RankData();
				rankdata.name = lrs.getString("name");
				rankdata.playtick = lrs.getInt("playtick");
				ranklist.add(rankdata);
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	//ランキング表示用に投票数のカラムだけ全員分引っ張る
	public boolean setRanking_p_vote() {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		List<RankData> ranklist = SeichiAssist.ranklist_p_vote;
		ranklist.clear();
		String command = "select name,p_vote from " + databaseName + "." + table
				+ " order by p_vote desc";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				RankData rankdata = new RankData();
				rankdata.name = lrs.getString("name");
				rankdata.p_vote = lrs.getInt("p_vote");
				ranklist.add(rankdata);
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	//ランキング表示用にプレミアムエフェクトポイントのカラムだけ全員分引っ張る
	public boolean setRanking_premiumeffectpoint() {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		List<RankData> ranklist = SeichiAssist.ranklist_premiumeffectpoint;
		ranklist.clear();
		String command = "select name,premiumeffectpoint from " + databaseName + "." + table
				+ " order by premiumeffectpoint desc";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				RankData rankdata = new RankData();
				rankdata.name = lrs.getString("name");
				rankdata.premiumeffectpoint = lrs.getInt("premiumeffectpoint");
				ranklist.add(rankdata);
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		return true;
	}
	//ランキング表示用に上げたりんご数のカラムだけ全員分引っ張る
	public boolean setRanking_p_apple() {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		List<RankData> ranklist = SeichiAssist.ranklist_p_apple;
		SeichiAssist.allplayergiveapplelong = 0;
		ranklist.clear();

		String command = "select name,p_apple from " + databaseName + "." + table
				+ " order by p_apple desc";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				RankData rankdata = new RankData();
				rankdata.name = lrs.getString("name");
				rankdata.p_apple = lrs.getInt("p_apple");
				ranklist.add(rankdata);
				SeichiAssist.allplayergiveapplelong += rankdata.p_apple;
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	//プレイヤーレベル全リセット
	public ActionStatus resetAllPlayerLevel(){
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String command = "update " + databaseName + "." + table
				+ " set level = 1";
		return executeCommand(command);
	}

	//プレイヤーのレベルと整地量をセット
	public ActionStatus resetPlayerLevelandBreaknum(UUID uuid){
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = uuid.toString();
		PlayerData playerdata = SeichiAssist.playermap.get(uuid);
		int level = playerdata.level;
		long totalbreaknum = playerdata.totalbreaknum;

		String command = "update " + databaseName + "." + table
				+ " set"

				+ " level = " + level
				+ ",totalbreaknum = " + totalbreaknum;

		//最後の処理
		command = command + " where uuid like '" + struuid + "'";

		return executeCommand(command);
	}

	//プレイヤーのレベルと整地量をセット(プレイヤーデータが無い場合)
	public ActionStatus resetPlayerLevelandBreaknum(UUID uuid, int level){
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = uuid.toString();
		//PlayerData playerdata = SeichiAssist.playermap.get(uuid);
		int totalbreaknum = SeichiAssist.levellist.get(level-1);

		String command = "update " + databaseName + "." + table
				+ " set"

				+ " level = " + level
				+ ",totalbreaknum = " + totalbreaknum;

		//最後の処理
		command = command + " where uuid like '" + struuid + "'";

		return executeCommand(command);
	}



	//全員に詫びガチャの配布
	public ActionStatus addAllPlayerBug(int amount){
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String command = "update " + databaseName + "." + table
				+ " set numofsorryforbug = numofsorryforbug + " + amount;
		return executeCommand(command);
	}

	//指定プレイヤーの四次元ポケットの中身取得
	public Inventory selectInventory(UUID uuid){
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = uuid.toString();
		Inventory inventory = null;
		String command = "select inventory from " + databaseName + "." + table
				+ " where uuid like '" + struuid + "'";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				inventory = BukkitSerialization.fromBase64(lrs.getString("inventory"));
			}
		} catch (SQLException | IOException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return null;
		}
		return inventory;
	}

	//指定プレイヤーのlastquitを取得
	public String selectLastQuit(String name){
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String lastquit = "";
		String command = "select lastquit from " + databaseName + "." + table
				+ " where name = '" + name + "'";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				lastquit = lrs.getString("lastquit");
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return null;
		}
		return lastquit;
	}

	//lastquitがdays日以上(または未登録)のプレイヤー名を配列で取得
	public Map<UUID, String> selectLeavers(int days){
		Map<UUID, String> leavers = new HashMap<>();
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String command = "select name, uuid from " + databaseName + "." + table
				+ " where ((lastquit <= date_sub(curdate(), interval " + days + " day))"
				+ " or (lastquit is null)) and (name != '') and (uuid != '')";
		try (ResultSet lrs = stmt.executeQuery(command)) {
			while (lrs.next()) {
				try {
					//結果のStringをUUIDに変換
					UUID uuid = UUID.fromString(lrs.getString("uuid"));
					if (leavers.containsKey(uuid)) {
						java.lang.System.out.println("playerdataにUUIDが重複しています: " + lrs.getString("uuid"));
					} else {
						//HashMapにUUIDとnameを登録
						leavers.put(uuid, lrs.getString("name"));
					}
				} catch (IllegalArgumentException e) {
					java.lang.System.out.println("不適切なUUID: " + lrs.getString("name") + ": " + lrs.getString("uuid"));
				}
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return null;
		}
		return leavers;
	}

	//
	public ActionStatus addPremiumEffectBuy(PlayerData playerdata,
											ActiveSkillPremiumEffect effect) {
		String table = SeichiAssist.DONATEDATA_TABLENAME;
		//
		String command = "insert into " + databaseName + "." + table
				+ " (playername,playeruuid,effectnum,effectname,usepoint,date) "
				+ "value("
				+ "'" + playerdata.name + "',"
				+ "'" + playerdata.uuid.toString() + "',"
				+ effect.getNum() + ","
				+ "'" + effect.getsqlName() + "',"
				+ effect.getUsePoint() + ","
				+ "cast( now() as datetime )"
				+ ")";

		return executeCommand(command);
	}
	public ActionStatus addDonate(String name, int point) {
		String table = SeichiAssist.DONATEDATA_TABLENAME;
		String command = "insert into " + databaseName + "." + table
				+ " (playername,getpoint,date) "
				+ "value("
				+ "'" + name + "',"
				+ point + ","
				+ "cast( now() as datetime )"
				+ ")";
		return executeCommand(command);
	}

	public boolean loadDonateData(PlayerData playerdata, Inventory inventory) {
		ItemStack itemstack;
		ItemMeta itemmeta;
		Material material;
		List<String> lore;
		int count = 0;
		ActiveSkillPremiumEffect[] effect = ActiveSkillPremiumEffect.values();

		String table = SeichiAssist.DONATEDATA_TABLENAME;
		String command = "select * from " + databaseName + "." + table + " where playername = '" + playerdata.name + "'";
		try (ResultSet lrs = stmt.executeQuery(command)){
			while (lrs.next()) {
				//ポイント購入の処理
				if(lrs.getInt("getpoint")>0){
					itemstack = new ItemStack(Material.DIAMOND);
					itemmeta = Bukkit.getItemFactory().getItemMeta(Material.DIAMOND);
					itemmeta.setDisplayName(ChatColor.AQUA + "" + ChatColor.UNDERLINE + "" + ChatColor.BOLD + "寄付");
					lore = Arrays.asList(ChatColor.RESET + "" +  ChatColor.GREEN + "" + "金額：" + lrs.getInt("getpoint")*100,
							ChatColor.RESET + "" +  ChatColor.GREEN + "" + "プレミアムエフェクトポイント：+" + lrs.getInt("getpoint"),
							ChatColor.RESET + "" +  ChatColor.GREEN + "" + "日時：" + lrs.getString("date")
					);
					itemmeta.setLore(lore);
					itemstack.setItemMeta(itemmeta);
					inventory.setItem(count,itemstack);
				}else if(lrs.getInt("usepoint")>0){
					int num = lrs.getInt("effectnum")-1;
					material = effect[num].getMaterial();
					itemstack = new ItemStack(material);
					itemmeta = Bukkit.getItemFactory().getItemMeta(material);
					itemmeta.setDisplayName(ChatColor.RESET + "" +  ChatColor.YELLOW + "購入エフェクト：" + effect[num].getName());
					lore = Arrays.asList(ChatColor.RESET + "" +  ChatColor.GOLD + "" + "プレミアムエフェクトポイント： -" + lrs.getInt("usepoint"),
							ChatColor.RESET + "" +  ChatColor.GOLD + "" + "日時：" + lrs.getString("date")
					);
					itemmeta.setLore(lore);
					itemstack.setItemMeta(itemmeta);
					inventory.setItem(count,itemstack);
				}
				count ++;
			}
		} catch (SQLException e) {
			java.lang.System.out.println("sqlクエリの実行に失敗しました。以下にエラーを表示します");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	public boolean saveShareInv(Player player, PlayerData playerdata, String data) {
		if (!playerdata.shareinvcooldownflag) {
			player.sendMessage(ChatColor.RED + "しばらく待ってからやり直してください");
			return false;
		}
		//連打による負荷防止の為クールダウン処理
		new CoolDownTaskRunnable(player, CoolDownTaskRunnable.SHAREINV).runTaskLater(plugin, 200);
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = playerdata.uuid.toString();
		String command = "SELECT shareinv FROM " + databaseName + "." + table + " " +
				"WHERE uuid = '" + struuid + "'";
		try (ResultSet lrs = stmt.executeQuery(command)) {
			lrs.next();
			String shareinv = lrs.getString("shareinv");
			lrs.close();
			if (shareinv != null && !shareinv.equals("")) {
				player.sendMessage(ChatColor.RED + "既にアイテムが収納されています");
				return false;
			}
			command = "UPDATE " + databaseName + "." + table + " " +
					"SET shareinv = '" + data + "' " +
					"WHERE uuid = '" + struuid + "'";
			if (executeCommand(command) == Fail) {
				player.sendMessage(ChatColor.RED + "アイテムの収納に失敗しました");
				Bukkit.getLogger().warning(Util.getName(player) + " sql failed. -> saveShareInv(executeCommand failed)");
				return false;
			}
		} catch (SQLException e) {
			player.sendMessage(ChatColor.RED + "共有インベントリにアクセスできません");
			Bukkit.getLogger().warning(Util.getName(player) + " sql failed. -> clearShareInv(SQLException)");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	public String loadShareInv(Player player, PlayerData playerdata) {
		if(!playerdata.shareinvcooldownflag){
			player.sendMessage(ChatColor.RED + "しばらく待ってからやり直してください");
			return null;
		}
		//連打による負荷防止の為クールダウン処理
		new CoolDownTaskRunnable(player,CoolDownTaskRunnable.SHAREINV).runTaskLater(plugin,200);
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = playerdata.uuid.toString();
		String command = "SELECT shareinv FROM " + databaseName + "." + table + " " +
				"WHERE uuid = '" + struuid + "'";
		String shareinv = null;
		try (ResultSet lrs = stmt.executeQuery(command)) {
			lrs.next();
			shareinv = lrs.getString("shareinv");
		} catch (SQLException e) {
			player.sendMessage(ChatColor.RED + "共有インベントリにアクセスできません");
			Bukkit.getLogger().warning(Util.getName(player) + " sql failed. -> loadShareInv");
			e.printStackTrace();
		}
		return shareinv;
	}

	public boolean clearShareInv(Player player, PlayerData playerdata) {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String struuid = playerdata.uuid.toString();
		String command = "UPDATE " + databaseName + "." + table + " " +
				"SET shareinv = '' " +
				"WHERE uuid = '" + struuid + "'";
		if (executeCommand(command) == Fail) {
			player.sendMessage(ChatColor.RED + "アイテムのクリアに失敗しました");
			Bukkit.getLogger().warning(Util.getName(player) + " sql failed. -> clearShareInv");
			return false;
		}
		return true;
	}

	/**
	 * 実績予約領域書き換え処理
	 *
	 * @param sender 発行Player
	 * @param targetName 対象Playerのname
	 * @param achvNo 対象実績No
	 * @return 成否…true: 成功、false: 失敗
	 */
	public boolean writegiveachvNo(Player sender, String targetName, String achvNo) {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String select = "SELECT giveachvNo FROM " + databaseName + "." + table + " " +
				"WHERE name LIKE '" + targetName + "'";
		String update = "UPDATE " + databaseName + "." + table + " " +
				" SET giveachvNo = " + achvNo +
				" WHERE name LIKE '" + targetName + "'";

		// selectで確認
		try (ResultSet lrs = stmt.executeQuery(select)) {
			// 初回のnextがnull→データが1件も無い場合
			if (!lrs.next()) {
				sender.sendMessage(ChatColor.RED + "" + targetName + " はデータベースに登録されていません");
				return false;
			}
			// 現在予約されている値を取得
			int giveachvNo = lrs.getInt("giveachvNo");
			// 既に予約がある場合
			if (giveachvNo != 0) {
				sender.sendMessage(ChatColor.RED + "" + targetName + " には既に実績No " + giveachvNo + " が予約されています");
				return false;
			}
			lrs.close();
			// 実績を予約
			stmt.executeUpdate(update);
		} catch (SQLException e) {
			sender.sendMessage(ChatColor.RED + "実績の予約に失敗しました");
			Bukkit.getLogger().warning(Util.getName(sender) + " sql failed. -> writegiveachvNo");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	// anniversary変更
	public boolean setAnniversary(boolean anniversary, UUID uuid) {
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String command = "UPDATE " + databaseName + "." + table + " " +
				"SET anniversary = " + anniversary;
		if (uuid != null) {
			command += " WHERE uuid = '" + uuid.toString() + "'";
		}
		if (executeCommand(command) == Fail) {
			Bukkit.getLogger().warning("sql failed. -> setAnniversary");
			return false;
		}
		return true;
	}

	public boolean setContribute(CommandSender sender, String targetName, int p){

		int point;

		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String select = "SELECT contribute_point FROM " + databaseName + "." + table + " " +
				"WHERE name LIKE '" + targetName + "'";

		// selectで確認
		try (ResultSet lrs = stmt.executeQuery(select)) {
			// 初回のnextがnull→データが1件も無い場合
			if (!lrs.next()) {
				sender.sendMessage(ChatColor.RED + "" + targetName + " はデータベースに登録されていません");
				return false;
			}
			//今までのポイントを加算して計算
			point = p + lrs.getInt("contribute_point");
		} catch (SQLException e) {
			sender.sendMessage(ChatColor.RED + "貢献度ptの取得に失敗しました");
			Bukkit.getLogger().warning(Util.getName(targetName) + " sql failed. -> contribute_point");
			e.printStackTrace();
			return false;
		}

		try {
			String update = "UPDATE " + databaseName + "." + table + " " +
					" SET contribute_point = " + point +
					" WHERE name LIKE '" + targetName + "'";

			stmt.executeUpdate(update);
		} catch (SQLException e) {
			sender.sendMessage(ChatColor.RED + "貢献度ptの変更に失敗しました");
			Bukkit.getLogger().warning(Util.getName(targetName) + " sql failed. -> contribute_point");
			e.printStackTrace();
			return false;
		}
		return true;
	}

	public boolean addChainVote (String name){
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd");
		String lastvote;
		String table = SeichiAssist.PLAYERDATA_TABLENAME;
		String select = "SELECT lastvote FROM " + databaseName + "." + table + " " +
				"WHERE name LIKE '" + name + "'";
		try (ResultSet lrs = stmt.executeQuery(select)) {
			// 初回のnextがnull→データが1件も無い場合
			if (!lrs.next()) {
				return false;
			}

			if(lrs.getString("lastvote") == null || lrs.getString("lastvote").equals("")){
				lastvote = sdf.format(cal.getTime());
			}else {
				lastvote = lrs.getString("lastvote");
			}

			lrs.close();

			String update = "UPDATE " + databaseName + "." + table + " " +
					" SET lastvote = '" + sdf.format(cal.getTime()) + "'" +
					" WHERE name LIKE '" + name + "'";

			stmt.executeUpdate(update);
		}catch (SQLException e) {
			Bukkit.getLogger().warning(Util.getName(name) + " sql failed. -> lastvote");
			e.printStackTrace();
			return false;
		}
		select = "SELECT chainvote FROM " + databaseName + "." + table + " " +
				"WHERE name LIKE '" + name + "'";
		try (ResultSet lrs = stmt.executeQuery(select)) {
			// 初回のnextがnull→データが1件も無い場合
			if (!lrs.next()) {
				return false;
			}
			int count = lrs.getInt("chainvote");
			try {
				Date TodayDate = sdf.parse(sdf.format(cal.getTime()));
				Date LastDate = sdf.parse(lastvote);
				long TodayLong = TodayDate.getTime();
				long LastLong = LastDate.getTime();

				long datediff = (TodayLong - LastLong)/(1000 * 60 * 60 * 24 );
				if(datediff <= 1 || datediff >= 0){
					count ++ ;
				}else {
					count = 1;
				}
				//プレイヤーがオンラインの時即時反映させる
				Player player = Bukkit.getServer().getPlayer(name);
				if (player != null) {
					//UUIDを取得
					UUID givenuuid = player.getUniqueId();
					//playerdataを取得
					PlayerData playerdata = SeichiAssist.playermap.get(givenuuid);

					playerdata.ChainVote ++;
				}
			} catch (ParseException e) {
				e.printStackTrace();
			}

			lrs.close();

			String update = "UPDATE " + databaseName + "." + table + " " +
					" SET chainvote = " + count +
					" WHERE name LIKE '" + name + "'";

			stmt.executeUpdate(update);
		}catch (SQLException e) {
			Bukkit.getLogger().warning(Util.getName(name) + " sql failed. -> chainvote");
			e.printStackTrace();
			return false;
		}
		return true;
	}
}
