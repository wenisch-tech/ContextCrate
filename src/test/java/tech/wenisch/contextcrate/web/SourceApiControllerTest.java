package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.*;

class SourceApiControllerTest {
  @Test
  void gitTokenIsRedactedButPresenceIsReported() {
    SourceService service = mock(SourceService.class);
    CrateAccessService access = mock(CrateAccessService.class);
    ConfigurationCodec crawl = new ConfigurationCodec(new ObjectMapper());
    SourceConfigurationCodec codec = new SourceConfigurationCodec(new ObjectMapper(), crawl);
    UUID crateId = UUID.randomUUID(), sourceId = UUID.randomUUID();
    Source source = new Source(sourceId, crateId, "Repository", null, ConnectorType.GIT,
        codec.write(SourceConfiguration.git("https://example.com/repo.git")));
    when(service.require(crateId, sourceId)).thenReturn(source);

    var response = new SourceApiController(service, codec, access).get(crateId, sourceId);

    assertThat(response.configuration().git().repositoryUrl()).isEqualTo("https://example.com/repo.git");
    verify(access).require(crateId, CrateMember.Role.VIEWER);
  }
}
