import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

// Custom exception for ATM operations
class ATMException extends Exception {
    public ATMException(String message) {
        super(message);
    }
}

// Account class with encapsulation
class Account {
    private String accountNumber;
    private String holderName;
    private String pin;
    private double balance;
    private ArrayList<String> transactionHistory;
    private static int accountCounter = 1001;

    public Account(String holderName, String pin, double initialDeposit) {
        this.accountNumber = String.valueOf(accountCounter++);
        this.holderName = holderName;
        this.pin = pin;
        this.balance = initialDeposit;
        this.transactionHistory = new ArrayList<>();
        addTransaction("Account created with initial deposit: ₹" + initialDeposit);
    }

    public String getAccountNumber() { return accountNumber; }
    public String getHolderName() { return holderName; }
    public double getBalance() { return balance; }
    public boolean verifyPin(String pinToCheck) { return this.pin.equals(pinToCheck); }

    public void addTransaction(String transaction) {
        String timestamp = new Date().toString();
        transactionHistory.add(timestamp + " | " + transaction);
    }

    public void deposit(double amount) throws ATMException {
        if (amount <= 0) throw new ATMException("Amount must be positive");
        balance += amount;
        addTransaction("Deposited: ₹" + amount + " | Balance: ₹" + balance);
    }

    public void withdraw(double amount) throws ATMException {
        if (amount <= 0) throw new ATMException("Amount must be positive");
        if (amount > balance) throw new ATMException("Insufficient balance. Available: ₹" + balance);
        balance -= amount;
        addTransaction("Withdrew: ₹" + amount + " | Balance: ₹" + balance);
    }

    public void transfer(Account toAccount, double amount) throws ATMException {
        if (amount <= 0) throw new ATMException("Transfer amount must be positive");
        if (amount > balance) throw new ATMException("Insufficient balance for transfer");

        this.balance -= amount;
        toAccount.balance += amount;

        this.addTransaction("Transferred ₹" + amount + " to Account " + toAccount.accountNumber);
        toAccount.addTransaction("Received ₹" + amount + " from Account " + this.accountNumber);
    }

    public ArrayList<String> getTransactionHistory() {
        return new ArrayList<>(transactionHistory);
    }
}

// Main ATM Server
public class ATMServer {
    private static HashMap<String, Account> accounts = new HashMap<>();
    private static HashMap<String, String> sessionTokens = new HashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Serve static files
        server.createContext("/", new StaticFileHandler());

        // API endpoints
        server.createContext("/api/create-account", new CreateAccountHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/logout", new LogoutHandler());
        server.createContext("/api/deposit", new DepositHandler());
        server.createContext("/api/withdraw", new WithdrawHandler());
        server.createContext("/api/transfer", new TransferHandler());
        server.createContext("/api/balance", new BalanceHandler());
        server.createContext("/api/history", new HistoryHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("✅ ATM Server started at http://localhost:8080");
        System.out.println("📱 Open this URL in your browser");
    }

    // Handle static HTML/CSS files
    static class StaticFileHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            String filePath = "." + path;
            File file = new File(filePath);

            if (!file.exists() || file.isDirectory()) {
                String response = "404 Not Found";
                exchange.sendResponseHeaders(404, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
                return;
            }

            String mimeType = path.endsWith(".css") ? "text/css" : "text/html";
            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.sendResponseHeaders(200, file.length());

            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
            exchange.close();
        }
    }

    // Helper to send JSON response
    static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, json.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(json.getBytes());
        os.close();
    }

    // Get account from session
    static Account getAccountFromSession(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;

        String[] parts = cookie.split("=");
        if (parts.length != 2) return null;

        String token = parts[1];
        String accNo = sessionTokens.get(token);
        if (accNo == null) return null;

        return accounts.get(accNo);
    }

    // Create Account API
    static class CreateAccountHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("&");

            String name = "", pin = "";
            double deposit = 0;

            for (String param : params) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (key.equals("name")) name = value;
                    if (key.equals("pin")) pin = value;
                    if (key.equals("deposit")) deposit = Double.parseDouble(value);
                }
            }

            if (name.isEmpty() || pin.length() != 4 || deposit < 500) {
                sendJson(exchange, 400, "{\"error\":\"Invalid input. PIN must be 4 digits, deposit minimum ₹500\"}");
                return;
            }

            Account newAccount = new Account(name, pin, deposit);
            accounts.put(newAccount.getAccountNumber(), newAccount);

            String json = String.format("{\"success\":true,\"accountNumber\":\"%s\",\"message\":\"Account created successfully\"}",
                    newAccount.getAccountNumber());
            sendJson(exchange, 200, json);
        }
    }

    // Login API
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("&");

            String accNo = "", pin = "";
            for (String param : params) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (key.equals("accountNumber")) accNo = value;
                    if (key.equals("pin")) pin = value;
                }
            }

            Account account = accounts.get(accNo);
            if (account == null || !account.verifyPin(pin)) {
                sendJson(exchange, 401, "{\"error\":\"Invalid credentials\"}");
                return;
            }

            String token = UUID.randomUUID().toString();
            sessionTokens.put(token, accNo);

            String json = String.format("{\"success\":true,\"token\":\"%s\",\"holderName\":\"%s\",\"balance\":%.2f,\"accountNumber\":\"%s\"}",
                    token, account.getHolderName(), account.getBalance(), accNo);

            exchange.getResponseHeaders().set("Set-Cookie", "token=" + token + "; Path=/");
            sendJson(exchange, 200, json);
        }
    }

    // Logout API
    static class LogoutHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null && cookie.contains("token=")) {
                String token = cookie.split("=")[1];
                sessionTokens.remove(token);
            }
            exchange.getResponseHeaders().set("Set-Cookie", "token=; Max-Age=0; Path=/");
            sendJson(exchange, 200, "{\"success\":true}");
        }
    }

    // Deposit API
    static class DepositHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Account account = getAccountFromSession(exchange);
            if (account == null) {
                sendJson(exchange, 401, "{\"error\":\"Not logged in\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("=");
            double amount = Double.parseDouble(params[1]);

            try {
                account.deposit(amount);
                String json = String.format("{\"success\":true,\"balance\":%.2f,\"message\":\"Deposited ₹%.2f successfully\"}",
                        account.getBalance(), amount);
                sendJson(exchange, 200, json);
            } catch (ATMException e) {
                sendJson(exchange, 400, String.format("{\"error\":\"%s\"}", e.getMessage()));
            }
        }
    }

    // Withdraw API
    static class WithdrawHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Account account = getAccountFromSession(exchange);
            if (account == null) {
                sendJson(exchange, 401, "{\"error\":\"Not logged in\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("=");
            double amount = Double.parseDouble(params[1]);

            try {
                account.withdraw(amount);
                String json = String.format("{\"success\":true,\"balance\":%.2f,\"message\":\"Withdrew ₹%.2f successfully\"}",
                        account.getBalance(), amount);
                sendJson(exchange, 200, json);
            } catch (ATMException e) {
                sendJson(exchange, 400, String.format("{\"error\":\"%s\"}", e.getMessage()));
            }
        }
    }

    // Transfer API
    static class TransferHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Account fromAccount = getAccountFromSession(exchange);
            if (fromAccount == null) {
                sendJson(exchange, 401, "{\"error\":\"Not logged in\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] params = body.split("&");

            String toAccNo = "";
            double amount = 0;
            for (String param : params) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (key.equals("toAccount")) toAccNo = value;
                    if (key.equals("amount")) amount = Double.parseDouble(value);
                }
            }

            Account toAccount = accounts.get(toAccNo);
            if (toAccount == null) {
                sendJson(exchange, 400, "{\"error\":\"Destination account not found\"}");
                return;
            }

            try {
                fromAccount.transfer(toAccount, amount);
                String json = String.format("{\"success\":true,\"balance\":%.2f,\"message\":\"Transferred ₹%.2f to Account %s\"}",
                        fromAccount.getBalance(), amount, toAccNo);
                sendJson(exchange, 200, json);
            } catch (ATMException e) {
                sendJson(exchange, 400, String.format("{\"error\":\"%s\"}", e.getMessage()));
            }
        }
    }

    // Balance API
    static class BalanceHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Account account = getAccountFromSession(exchange);
            if (account == null) {
                sendJson(exchange, 401, "{\"error\":\"Not logged in\"}");
                return;
            }

            String json = String.format("{\"balance\":%.2f,\"holderName\":\"%s\",\"accountNumber\":\"%s\"}",
                    account.getBalance(), account.getHolderName(), account.getAccountNumber());
            sendJson(exchange, 200, json);
        }
    }

    // Transaction History API
    static class HistoryHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            Account account = getAccountFromSession(exchange);
            if (account == null) {
                sendJson(exchange, 401, "{\"error\":\"Not logged in\"}");
                return;
            }

            ArrayList<String> history = account.getTransactionHistory();
            StringBuilder json = new StringBuilder("{\"history\":[");
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(history.get(i).replace("\"", "\\\"")).append("\"");
            }
            json.append("]}");
            sendJson(exchange, 200, json.toString());
        }
    }
}