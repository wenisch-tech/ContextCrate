package db.migration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import org.flywaydb.core.api.migration.*;

/** Moves the pre-v11 Git credentials from a source to each attached ingestion job. */
public class V11__move_git_credentials_to_ingestion_jobs extends BaseJavaMigration {
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void migrate(Context context) throws Exception {
    try (PreparedStatement select = context.getConnection().prepareStatement(
        "select s.id, s.configuration_json, j.id, j.configuration_json "
            + "from source s join ingestion_job j on j.source_id = s.id "
            + "where s.connector_type = 'GIT'")) {
      try (ResultSet rows = select.executeQuery();
          PreparedStatement updateSource = context.getConnection().prepareStatement(
              "update source set configuration_json = ? where id = ?");
          PreparedStatement updateJob = context.getConnection().prepareStatement(
              "update ingestion_job set configuration_json = ? where id = ?")) {
        while (rows.next()) {
          JsonNode source = mapper.readTree(rows.getString(2));
          ObjectNode git = source.path("git").isObject()
              ? (ObjectNode) source.path("git") : null;
          if (git == null) continue;
          JsonNode username = git.remove("username");
          JsonNode token = git.remove("token");
          ObjectNode job = (ObjectNode) mapper.readTree(rows.getString(4));
          if (job.path("git").isObject()) {
            ObjectNode jobGit = (ObjectNode) job.path("git");
            if (username != null && !username.isNull() && !jobGit.hasNonNull("username"))
              jobGit.set("username", username);
            if (token != null && !token.isNull() && !jobGit.hasNonNull("token"))
              jobGit.set("token", token);
            updateJob.setString(1, mapper.writeValueAsString(job));
            updateJob.setObject(2, rows.getObject(3));
            updateJob.addBatch();
          }
          updateSource.setString(1, mapper.writeValueAsString(source));
          updateSource.setObject(2, rows.getObject(1));
          updateSource.addBatch();
        }
        updateSource.executeBatch();
        updateJob.executeBatch();
      }
    }
  }
}
