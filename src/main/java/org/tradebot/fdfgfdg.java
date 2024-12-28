package org.tradebot;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class fdfgfdg {


    private static final String API_KEY = "YOUR_API_KEY";
    private static final String SECRET_KEY = "YOUR_SECRET_KEY";
    private static final String BASE_URL = "https://fapi.binance.com";

    // Метод для отправки запроса
    private static String sendRequest(String endpoint, String method, Map<String, String> params) throws Exception {
        long timestamp = System.currentTimeMillis();
        params.put("timestamp", String.valueOf(timestamp));
        params.put("signature", generateSignature(params));

        URL url = new URL(BASE_URL + endpoint + "?" + getParamsString(params));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("X-MBX-APIKEY", API_KEY);
        conn.setDoOutput(true);

        if (method.equals("POST")) {
            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
            writer.write("");
            writer.flush();
            writer.close();
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        return response.toString();
    }

    // Метод для создания стоп-лосс или тейк-профит ордера
    public static String createStopOrTakeProfitOrder(
            String symbol,
            String side,
            String type,
            double quantity,
            double price,
            boolean reduceOnly
    ) throws Exception {

        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", type);
        params.put("quantity", String.valueOf(quantity));
        params.put("price", String.valueOf(price));
        params.put("timeInForce", "GTC"); // Good-Till-Canceled
        params.put("reduceOnly", String.valueOf(reduceOnly)); // Закрываем только существующую позицию

        return sendRequest("/fapi/v1/order", "POST", params);
    }

    // Хелпер для создания подписи
    private static String generateSignature(Map<String, String> params) throws Exception {
        String query = getParamsString(params);
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY.getBytes(), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(query.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static String getParamsString(Map<String, String> params) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            result.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        }
        String resultString = result.toString();
        return resultString.length() > 0 ? resultString.substring(0, resultString.length() - 1) : resultString;
    }

    // Пример использования
    public static void main(String[] args) {
        try {
            String symbol = "BTCUSDT";
            double quantity = 0.01; // 1% позиции
            double stopLossPrice = 45000.0;
            double takeProfitPrice = 55000.0;

            // Создание стоп-лосса
            String stopLossOrder = createStopOrTakeProfitOrder(symbol, "SELL", "STOP_MARKET", quantity, stopLossPrice, true);
            System.out.println("Stop-Loss Order: " + stopLossOrder);

            // Создание тейк-профита
            String takeProfitOrder = createStopOrTakeProfitOrder(symbol, "SELL", "TAKE_PROFIT_MARKET", quantity, takeProfitPrice, true);
            System.out.println("Take-Profit Order: " + takeProfitOrder);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}