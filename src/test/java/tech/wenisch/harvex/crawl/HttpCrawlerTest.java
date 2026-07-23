package tech.wenisch.harvex.crawl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static tech.wenisch.harvex.domain.PipelineTypes.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.service.*;
import tech.wenisch.harvex.storage.ArtifactStore;

@ExtendWith(MockitoExtension.class)
class HttpCrawlerTest {

    @Mock
    private FrontierEntryRepository frontier;

    @Mock
    private CrawlRunRepository runs;

    @Mock
    private ConfigurationCodec codec;

    @Mock
    private UrlPolicy urls;

    @Mock
    private RobotsService robots;

    @Mock
    private HostPoliteness politeness;

    @Mock
    private ArtifactStore artifacts;

    @Mock
    private FetchRecordRepository fetches;

    @Mock
    private PipelineQueue queue;

    @Mock
    private RunLogger runLogger;

    @Mock
    private HttpClient httpClient;

    @InjectMocks
    private HttpCrawler crawler;

    @TempDir
    Path tempDir;

    private UUID runId;
    private UUID entryId;
    private UUID fetchId;
    private FrontierEntry entry;
    private CrawlRun run;
    private CrawlConfiguration config;
    private PipelinePayload payload;

    @BeforeEach
    void setUp() throws Exception {
        runId = UUID.randomUUID();
        entryId = UUID.randomUUID();
        fetchId = UUID.randomUUID();

        entry = new FrontierEntry(entryId, runId, "https://example.com", "https://example.com", 0);
        entry.status(FrontierStatus.QUEUED);

        run = new CrawlRun(runId, UUID.randomUUID(), "{}");

         config = new CrawlConfiguration(
             new CrawlConfiguration.Scope(
                 "https://example.com",
                 Set.of("example.com"),
                 List.of(),
                 List.of(),
                 3,
                 100,
                 false,
                 false
             ),
             new CrawlConfiguration.Politeness(
                 "HarvexBot",
                 "contact@example.com",
                 true,
                 1,
                 1000,
                 5000
             ),
             new CrawlConfiguration.Reliability(
                 3,
                 1000,
                 10_000_000,
                 true,
                 CrawlConfiguration.RenderMode.HTTP_ONLY
             ),
             null,
             CrawlConfiguration.LoginConfiguration.defaults()
         );

        payload = new PipelinePayload(runId, entryId);

        // Inject mock HttpClient
        ReflectionTestUtils.setField(crawler, "client", httpClient);
    }

    @Test
    void fetch_shouldSkipWhenRunNotRunning() throws Exception {
        // Arrange
        run.status(RunStatus.CANCELLED);
        when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
        when(runs.findById(runId)).thenReturn(Optional.of(run));

        // Act
        crawler.fetch(payload, false);

        // Assert
        verify(frontier, never()).save(any());
        verify(fetches, never()).save(any());
        verify(queue, never()).publish(any());
    }

    @Test
    void fetch_shouldSkipWhenRobotsTxtDisallows() throws Exception {
        // Arrange
        when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(codec.read(anyString())).thenReturn(config);
        when(robots.allowed(anyString(), anyString())).thenReturn(false);

        // Act
        crawler.fetch(payload, false);

        // Assert
        verify(frontier).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getStatus()).isEqualTo(FrontierStatus.EXCLUDED);
        verify(fetches, never()).save(any());
        verify(queue, never()).publish(any());
    }

    @Test
    void fetch_shouldHandleSuccessfulHttpFetch() throws Exception {
        // Arrange
        String content = "<html><body>Test content</body></html>";
        InputStream contentStream = new ByteArrayInputStream(content.getBytes());
        HttpHeaders headers = HttpHeaders.of(
            Map.of("Content-Type", List.of("text/html")),
            (k, v) -> true
        );

        // Use raw mock to avoid generic type issues
        HttpResponse rawResponse = mock(HttpResponse.class);
        when(rawResponse.statusCode()).thenReturn(200);
        when(rawResponse.body()).thenReturn(contentStream);
        when(rawResponse.headers()).thenReturn(headers);
        when(rawResponse.uri()).thenReturn(new java.net.URI("https://example.com"));

        when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(codec.read(anyString())).thenReturn(config);
        when(robots.allowed(anyString(), anyString())).thenReturn(true);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(rawResponse);

        ArtifactStore.ArtifactMetadata savedArtifact = new ArtifactStore.ArtifactMetadata(
            "test-key", "test-sha256", content.length()
        );
        when(artifacts.put(anyString(), any(InputStream.class), anyLong())).thenReturn(savedArtifact);

        // Act
        crawler.fetch(payload, false);

        // Assert
        verify(politeness).await(anyString(), anyLong());
        verify(frontier).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getStatus()).isEqualTo(FrontierStatus.FETCHED);

        verify(fetches).save(fetchRecordCaptor.capture());
        FetchRecord record = fetchRecordCaptor.getValue();
        assertThat(record.getOutcome()).isEqualTo(FetchOutcome.SUCCEEDED);
        assertThat(record.getContentType()).isEqualTo("text/html");

        verify(queue).publish(any(PipelineMessage.class));
    }

    @Test
    void fetch_shouldHandleFailedHttpFetch() throws Exception {
        // Arrange
        when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(codec.read(anyString())).thenReturn(config);
        when(robots.allowed(anyString(), anyString())).thenReturn(true);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("Connection failed"));

        // Act
        assertThatThrownBy(() -> crawler.fetch(payload, false))
            .isInstanceOf(IOException.class)
            .hasMessage("Connection failed");

        // Assert
        verify(frontier).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getStatus()).isEqualTo(FrontierStatus.FAILED);

        verify(fetches).save(fetchRecordCaptor.capture());
        FetchRecord record = fetchRecordCaptor.getValue();
        assertThat(record.getOutcome()).isEqualTo(FetchOutcome.FAILED);
    }

    @Test
    void fetch_shouldHandleTooLargeResponse() throws Exception {
        // Arrange
        when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(codec.read(anyString())).thenReturn(config);
        when(robots.allowed(anyString(), anyString())).thenReturn(true);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("maximum size exceeded"));

        // Act
        assertThatThrownBy(() -> crawler.fetch(payload, false))
            .isInstanceOf(IOException.class)
            .hasMessage("maximum size exceeded");

        // Assert
        verify(frontier).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getStatus()).isEqualTo(FrontierStatus.FAILED);

        verify(fetches).save(fetchRecordCaptor.capture());
        FetchRecord record = fetchRecordCaptor.getValue();
        assertThat(record.getOutcome()).isEqualTo(FetchOutcome.TOO_LARGE);
    }

    @Test
    void fetch_shouldHandleNonHtmlContent() throws Exception {
        // Arrange
        String content = "binary content";
        InputStream contentStream = new ByteArrayInputStream(content.getBytes());
        HttpHeaders headers = HttpHeaders.of(
            Map.of("Content-Type", List.of("application/pdf")),
            (k, v) -> true
        );

        // Use raw mock to avoid generic type issues
        HttpResponse rawResponse = mock(HttpResponse.class);
        when(rawResponse.statusCode()).thenReturn(200);
        when(rawResponse.body()).thenReturn(contentStream);
        when(rawResponse.headers()).thenReturn(headers);
        when(rawResponse.uri()).thenReturn(new java.net.URI("https://example.com"));

        when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(codec.read(anyString())).thenReturn(config);
        when(robots.allowed(anyString(), anyString())).thenReturn(true);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(rawResponse);

        ArtifactStore.ArtifactMetadata savedArtifact = new ArtifactStore.ArtifactMetadata(
            "test-key", "test-sha256", content.length()
        );
        when(artifacts.put(anyString(), any(InputStream.class), anyLong())).thenReturn(savedArtifact);

        // Act
        crawler.fetch(payload, false);

        // Assert
        verify(frontier).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getStatus()).isEqualTo(FrontierStatus.FETCHED);

        verify(fetches).save(fetchRecordCaptor.capture());
        FetchRecord record = fetchRecordCaptor.getValue();
        assertThat(record.getContentType()).isEqualTo("application/pdf");

        verify(queue, never()).publish(any(PipelineMessage.class));
    }

    @Test
    void getCookiesForRun_shouldReadSessionFile() throws Exception {
        // Arrange
        // Create session file in current working directory (where the method expects it)
        Path sessionFile = Path.of("session_" + runId + ".json");
        Files.writeString(sessionFile, "{\"name\":\"session\",\"value\":\"test-session\"}");

        try {
            // Test the private method using reflection
            java.lang.reflect.Method getCookiesMethod = HttpCrawler.class.getDeclaredMethod("getCookiesForRun", UUID.class, CrawlConfiguration.class);
            getCookiesMethod.setAccessible(true);

            // Act
            String cookies = (String) getCookiesMethod.invoke(crawler, runId, config);

            // Assert
            assertThat(cookies).isEqualTo("session=test-session");
        } finally {
            // Clean up
            Files.deleteIfExists(sessionFile);
        }
    }

    @Test
    void decodeHtmlEntities_shouldHandleBasicEntities() throws Exception {
        // Test the private decodeHtmlEntities method using reflection
        java.lang.reflect.Method decodeMethod = HttpCrawler.class.getDeclaredMethod("decodeHtmlEntities", String.class);
        decodeMethod.setAccessible(true);

        // Act & Assert
        assertThat(decodeMethod.invoke(crawler, "test")).isEqualTo("test");
        assertThat(decodeMethod.invoke(crawler, "&test")).isEqualTo("&test");
        assertThat(decodeMethod.invoke(crawler, "<tag>")).isEqualTo("<tag>");
    }

    @Test
    void charsetExtraction_shouldWorkCorrectly() throws Exception {
        // Test the private charset method using reflection
        java.lang.reflect.Method charsetMethod = HttpCrawler.class.getDeclaredMethod("charset", String.class);
        charsetMethod.setAccessible(true);

        // Test with charset
        assertThat(charsetMethod.invoke(crawler, "text/html; charset=UTF-8")).isEqualTo("UTF-8");

        // Test without charset
        assertThat(charsetMethod.invoke(crawler, "text/html")).isEqualTo("UTF-8");

        // Test with multiple parameters
        assertThat(charsetMethod.invoke(crawler, "text/html; param=value; charset=ISO-8859-1")).isEqualTo("ISO-8859-1");
    }

    @Test
    void extensionDetermination_shouldWorkCorrectly() throws Exception {
        // Test the private extension method using reflection
        java.lang.reflect.Method extensionMethod = HttpCrawler.class.getDeclaredMethod("extension", String.class);
        extensionMethod.setAccessible(true);

        assertThat(extensionMethod.invoke(crawler, "text/html")).isEqualTo(".html");
        assertThat(extensionMethod.invoke(crawler, "application/xhtml+xml")).isEqualTo(".html");
        assertThat(extensionMethod.invoke(crawler, "application/pdf")).isEqualTo(".bin");
        assertThat(extensionMethod.invoke(crawler, "image/jpeg")).isEqualTo(".bin");
    }

    // Argument captors for verification
    @Captor
    private ArgumentCaptor<FrontierEntry> entryCaptor;

    @Captor
    private ArgumentCaptor<FetchRecord> fetchRecordCaptor;

    @Captor
    private ArgumentCaptor<PipelineMessage> messageCaptor;

    @Test
    void isKeycloakLoginPage_shouldDetectKeycloakPages() throws Exception {
        // Test the private isKeycloakLoginPage method using reflection
        java.lang.reflect.Method isKeycloakMethod = HttpCrawler.class.getDeclaredMethod("isKeycloakLoginPage", String.class);
        isKeycloakMethod.setAccessible(true);

        // Test Keycloak-specific patterns
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body><div class='kc-form-login'></div></body></html>"))
            .isEqualTo(true);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body><form action='/realms/master/login'></form></body></html>"))
            .isEqualTo(true);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body><input name='execution' value='123'/><input name='client_id' value='test'/></body></html>"))
            .isEqualTo(true);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body><div id='kc-login'></div></body></html>"))
            .isEqualTo(true);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body>Some Keycloak content</body></html>"))
            .isEqualTo(true);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body>login-actions and client_id</body></html>"))
            .isEqualTo(true);

        // Test additional Keycloak patterns with double quotes
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body><input name=\"execution\" value=\"123\"/><input name=\"client_id\" value=\"test\"/></body></html>"))
            .isEqualTo(true);

        // Test non-Keycloak patterns
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body>Regular login form</body></html>"))
            .isEqualTo(false);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body><form action='/login'></form></body></html>"))
            .isEqualTo(false);
        assertThat(isKeycloakMethod.invoke(crawler, "<html><body>Some random content</body></html>"))
            .isEqualTo(false);
    }

    @Test
    void detectUsernameField_shouldFindCorrectFieldNames() throws Exception {
        // Test the private detectUsernameField method using reflection
        java.lang.reflect.Method detectMethod = HttpCrawler.class.getDeclaredMethod("detectUsernameField", String.class);
        detectMethod.setAccessible(true);

        // Test various username field patterns
        assertThat(detectMethod.invoke(crawler, "<html><body><input name='username-email'/></body></html>"))
            .isEqualTo("username-email");
        assertThat(detectMethod.invoke(crawler, "<html><body><input name='email'/></body></html>"))
            .isEqualTo("email");
        assertThat(detectMethod.invoke(crawler, "<html><body><input name='username'/></body></html>"))
            .isEqualTo("username");
        assertThat(detectMethod.invoke(crawler, "<html><body><input name='user_name'/></body></html>"))
            .isEqualTo("user_name");
        assertThat(detectMethod.invoke(crawler, "<html><body><input name='login-field'/></body></html>"))
            .isEqualTo("login-field");
        assertThat(detectMethod.invoke(crawler, "<html><body><input name='user_login'/></body></html>"))
            .isEqualTo("user_login");

        // Test default fallback
        assertThat(detectMethod.invoke(crawler, "<html><body>No username field</body></html>"))
            .isEqualTo("username");
    }

    @Test
    void extractKeycloakFormFields_shouldExtractKeycloakFields() throws Exception {
        // Test the private extractKeycloakFormFields method using reflection
        java.lang.reflect.Method extractMethod = HttpCrawler.class.getDeclaredMethod("extractKeycloakFormFields", String.class);
        extractMethod.setAccessible(true);

        String keycloakHtml = "<html><body>" +
                "<input type='hidden' name='execution' value='123'/>" +
                "<input type='hidden' name='client_id' value='test-client'/>" +
                "<input type='hidden' name='tab_id' value='tab1'/>" +
                "<input type='hidden' name='session_code' value='session123'/>" +
                "<input type='hidden' name='other_field' value='other-value'/>" +
                "</body></html>";

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) extractMethod.invoke(crawler, keycloakHtml);

        assertThat(result).containsEntry("execution", "123");
        assertThat(result).containsEntry("client_id", "test-client");
        assertThat(result).containsEntry("tab_id", "tab1");
        assertThat(result).containsEntry("session_code", "session123");
        assertThat(result).containsEntry("other_field", "other-value");
    }

    @Test
    void shouldPerformDirectLogin_shouldReturnTrueWhenDirectLoginEnabled() throws Exception {
        // Test the private shouldPerformDirectLogin method using reflection
        java.lang.reflect.Method shouldPerformDirectLoginMethod = HttpCrawler.class.getDeclaredMethod("shouldPerformDirectLogin", CrawlConfiguration.class);
        shouldPerformDirectLoginMethod.setAccessible(true);

        // Create config with direct login enabled
        CrawlConfiguration directLoginConfig = new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                "https://example.com",
                Set.of("example.com"),
                List.of(),
                List.of(),
                3,
                100,
                false,
                false
            ),
            new CrawlConfiguration.Politeness(
                "HarvexBot",
                "contact@example.com",
                true,
                1,
                1000,
                5000
            ),
            new CrawlConfiguration.Reliability(
                3,
                1000,
                10_000_000,
                true,
                CrawlConfiguration.RenderMode.HTTP_ONLY
            ),
            null,
            new CrawlConfiguration.LoginConfiguration(
                "https://example.com/login",
                "testuser",
                "testpass",
                "username",
                "password",
                "button[type='submit']",
                new CrawlConfiguration.SuccessDetection(null, null),
                true  // directLogin enabled
            )
        );

        // Create config with direct login disabled
        CrawlConfiguration noDirectLoginConfig = new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                "https://example.com",
                Set.of("example.com"),
                List.of(),
                List.of(),
                3,
                100,
                false,
                false
            ),
            new CrawlConfiguration.Politeness(
                "HarvexBot",
                "contact@example.com",
                true,
                1,
                1000,
                5000
            ),
            new CrawlConfiguration.Reliability(
                3,
                1000,
                10_000_000,
                true,
                CrawlConfiguration.RenderMode.HTTP_ONLY
            ),
            null,
            new CrawlConfiguration.LoginConfiguration(
                "https://example.com/login",
                "testuser",
                "testpass",
                "username",
                "password",
                "button[type='submit']",
                new CrawlConfiguration.SuccessDetection(null, null),
                false  // directLogin disabled
            )
        );

        // Create config with no login configuration
        CrawlConfiguration noLoginConfig = new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                "https://example.com",
                Set.of("example.com"),
                List.of(),
                List.of(),
                3,
                100,
                false,
                false
            ),
            new CrawlConfiguration.Politeness(
                "HarvexBot",
                "contact@example.com",
                true,
                1,
                1000,
                5000
            ),
            new CrawlConfiguration.Reliability(
                3,
                1000,
                10_000_000,
                true,
                CrawlConfiguration.RenderMode.HTTP_ONLY
            ),
            null,
            CrawlConfiguration.LoginConfiguration.defaults()
        );

        // Test direct login enabled
        assertThat(shouldPerformDirectLoginMethod.invoke(crawler, directLoginConfig))
            .isEqualTo(true);

        // Test direct login disabled
        assertThat(shouldPerformDirectLoginMethod.invoke(crawler, noDirectLoginConfig))
            .isEqualTo(false);

        // Test no login configuration
        assertThat(shouldPerformDirectLoginMethod.invoke(crawler, noLoginConfig))
            .isEqualTo(false);
    }
}
