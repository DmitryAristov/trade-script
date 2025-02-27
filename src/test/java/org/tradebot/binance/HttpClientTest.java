//package org.tradebot.binance;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.InjectMocks;
//import org.mockito.MockitoAnnotations;
//import org.tradebot.domain.APIError;
//import org.tradebot.domain.HTTPResponse;
//
//import java.net.HttpURLConnection;
//import java.util.HashMap;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//
//class HttpClientTest {
//
//    @InjectMocks
//    private HttpClient httpClient;
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//        httpClient = spy(new HttpClient());
//    }
//
//    @Test
//    void testGenerateSignature() throws Exception {
//        Map<String, String> params = new HashMap<>();
//        params.put("symbol", "BTCUSDT");
//        params.put("side", "BUY");
//        params.put("type", "LIMIT");
//
//        String signature = httpClient.generateSignature(params);
//
//        assertNotNull(signature);
//        assertNotNull(signature);
//        assertFalse(signature.isEmpty());
//    }
//
//    @Test
//    void testGetParamsString() {
//        Map<String, String> params = new HashMap<>();
//        params.put("symbol", "BTCUSDT");
//        params.put("side", "BUY");
//        params.put("type", "LIMIT");
//
//        String paramString = httpClient.getParamsString(params);
//        assertEquals("side=BUY&symbol=BTCUSDT&type=LIMIT", paramString);
//    }
//
//    @Test
//    void testSendRequest_SuccessResponse() throws Exception {
//        Map<String, String> params = new HashMap<>();
//        params.put("symbol", "BTCUSDT");
//        params.put("side", "BUY");
//        params.put("type", "LIMIT");
//
//        HttpURLConnection connection = mock(HttpURLConnection.class);
//        when(connection.getResponseCode()).thenReturn(200);
//
//        doReturn(HTTPResponse.success(200, "mocked-response")).when(httpClient)
//                .sendRequest(anyString(), anyString(), anyMap());
//        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v1/order", "POST", params);
//
//        assertTrue(response.isSuccess());
//        assertEquals("mocked-response", response.getValue());
//    }
//
//    @Test
//    void testSendRequest_ErrorResponse() throws Exception {
//        Map<String, String> params = new HashMap<>();
//        params.put("symbol", "BTCUSDT");
//        params.put("side", "BUY");
//        params.put("type", "LIMIT");
//
//        HttpURLConnection connection = mock(HttpURLConnection.class);
//        when(connection.getResponseCode()).thenReturn(400);
//        doReturn(HTTPResponse.error(500, "mocked-error")).when(httpClient)
//                .sendRequest(anyString(), anyString(), anyMap());
//
//        HTTPResponse<String> response = httpClient.sendRequest("/fapi/v1/order", "POST", params);
//
//        assertTrue(response.isError());
//        assertEquals("mocked-error", response.getError());
//    }
//}
