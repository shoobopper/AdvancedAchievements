package com.hm.achievement.db;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import javax.inject.Named;

import com.hm.achievement.category.NormalAchievements;
import com.hm.mcshared.file.CommentedYamlConfiguration;

/**
 * Class used to handle a PosgreSQL database. Note that some query methods are overriden as the SQL syntax is different
 * from other database types.
 * 
 * @author Pyves
 *
 */
public class PostgreSQLDatabaseManager extends AbstractDatabaseManager {

	public PostgreSQLDatabaseManager(@Named("main") CommentedYamlConfiguration mainConfig, Logger logger,
			Map<String, String> achievementsAndDisplayNames, DatabaseUpdater databaseUpdater) {
		super(mainConfig, logger, achievementsAndDisplayNames, databaseUpdater);
	}

	@Override
	void performPreliminaryTasks() throws ClassNotFoundException, UnsupportedEncodingException {
		Class.forName("org.postgresql.Driver");

		// Get parameters from the PostgreSQL config category.
		databaseAddress = mainConfig.getString("POSTGRESQL.Database", "jdbc:postgresql://localhost:5432/minecraft");
		databaseUser = URLEncoder.encode(mainConfig.getString("POSTGRESQL.User", "root"), StandardCharsets.UTF_8.name());
		databasePassword = URLEncoder.encode(mainConfig.getString("POSTGRESQL.Password", "root"),
				StandardCharsets.UTF_8.name());
	}

	@Override
	Connection createSQLConnection() throws SQLException {
		return DriverManager.getConnection(databaseAddress + "?autoReconnect=true" + additionalConnectionOptions + "&user="
				+ databaseUser + "&password=" + databasePassword);
	}

	@Override
	public void registerAchievement(UUID uuid, String achName, String achMessage) {
		// PostgreSQL has no REPLACE operator. We have to use the INSERT ... ON CONFLICT construct, which is available
		// for PostgreSQL 9.5+.
		String sql = "INSERT INTO " + prefix + "achievements VALUES (?,?,?,?)"
				+ " ON CONFLICT (playername,achievement) DO UPDATE SET (description,date)=(?,?)";
		((SQLWriteOperation) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setObject(1, uuid, Types.CHAR);
				ps.setString(2, achName);
				ps.setString(3, achMessage);
				ps.setDate(4, new Date(System.currentTimeMillis()));
				ps.setString(5, achMessage);
				ps.setDate(6, new Date(System.currentTimeMillis()));
				ps.execute();
			}
		}).executeOperation(pool, logger, "registering an achievement");
	}

	@Override
	public int updateAndGetConnection(UUID uuid, String date) {
		String dbName = NormalAchievements.CONNECTIONS.toDBName();
		String sqlRead = "SELECT " + dbName + " FROM " + prefix + dbName + " WHERE playername = ?";
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sqlRead)) {
				ps.setString(1, uuid.toString());
				ResultSet rs = ps.executeQuery();
				int connections = rs.next() ? rs.getInt(dbName) + 1 : 1;
				// PostgreSQL has no REPLACE operator. We have to use the INSERT ... ON CONFLICT construct, which is
				// available for PostgreSQL 9.5+.
				String sqlWrite = "INSERT INTO " + prefix + dbName + " VALUES (?,?,?)"
						+ " ON CONFLICT (playername) DO UPDATE SET (" + dbName + ",date)=(?,?)";
				((SQLWriteOperation) () -> {
					Connection writeConn = getSQLConnection();
					try (PreparedStatement writePrep = writeConn.prepareStatement(sqlWrite)) {
						writePrep.setObject(1, uuid, Types.CHAR);
						writePrep.setInt(2, connections);
						writePrep.setString(3, date);
						writePrep.setInt(4, connections);
						writePrep.setString(5, date);
						writePrep.execute();
					}
				}).executeOperation(pool, logger, "updating connection date and count");
				return connections;
			}
		}).executeOperation("handling connection event");
	}
}
