package com.cccece.authwithqq.util;

import com.cccece.authwithqq.database.DatabaseManager;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Handles CSV export and import for player data.
 */
public class CsvManager {
  private final DatabaseManager databaseManager;
  private final Logger logger;

  /**
   * Initializes the CsvManager.
   *
   * @param databaseManager The database manager.
   * @param logger The logger.
   */
  @SuppressFBWarnings(value = {"EI_EXPOSE_REP2"}, justification = "DatabaseManager and Logger instances are shared services, not meant for defensive copying.")
  public CsvManager(DatabaseManager databaseManager, Logger logger) {
    this.databaseManager = databaseManager;
    this.logger = logger;
  }

  /**
   * Exports all player data to a CSV file.
   *
   * @param file The file to export to.
   * @throws IOException If an I/O error occurs.
   */
  public void exportCsv(File file) throws IOException {
    List<String> metaKeys = databaseManager.getAllMetaKeys();
    List<String> headers = new ArrayList<>(Arrays.asList("UUID", "Name", "QQ", "Created"));
    headers.addAll(metaKeys);

    List<Map<String, String>> allData = databaseManager.getAllPlayersData();

    try (BufferedWriter writer = new BufferedWriter(
        new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
      // Write header
      writer.write(String.join(",", headers));
      writer.newLine();

      // Write data
      for (Map<String, String> row : allData) {
        List<String> line = new ArrayList<>();
        for (String header : headers) {
          line.add(row.getOrDefault(header, ""));
        }
        writer.write(String.join(",", line));
        writer.newLine();
      }
    }
  }

  /**
   * Imports player data from a CSV file.
   *
   * @param file The file to import from.
   * @throws IOException If an I/O error occurs.
   */
  public void importCsv(File file) throws IOException {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        return;
      }

      String[] headers = headerLine.split(",");
      String line;
      while ((line = reader.readLine()) != null) {
        String[] values = line.split(",", -1);
        if (values.length < 4) {
          continue;
        }

        String uuidStr = values[0];
        String name = values[1];
        long qq = 0;
        try {
          qq = Long.parseLong(values[2]);
        } catch (NumberFormatException ignored) {
          // Ignore
        }

        UUID uuid;
        try {
          uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
          continue;
        }

        // Update basic data
        databaseManager.addGuest(uuid, name);
        databaseManager.updateBinding(uuid, qq);

        // Update meta
        for (int i = 4; i < headers.length && i < values.length; i++) {
          String key = headers[i];
          String value = values[i];
          if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
            databaseManager.setMeta(uuid, key, value);
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Error during CSV import", e);
      throw new IOException("CSV Import failed", e);
    }
  }
}
