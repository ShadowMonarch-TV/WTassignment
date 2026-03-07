import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class Main {
    private static final Gson GSON = new Gson();
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Path STATIC_ROOT = Paths.get("public").toAbsolutePath().normalize();
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static void main(String[] args) throws IOException {
        try {
            Class.forName("org.postgresql.Driver");
            initDatabase();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database: " + e.getMessage(), e);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/health", Main::handleHealth);
        server.createContext("/api/register", Main::handleRegister);
        server.createContext("/api/login", Main::handleLogin);
        server.createContext("/api/questions", Main::handleQuestions);
        server.createContext("/api/submit", Main::handleSubmit);
        server.createContext("/api/leaderboard", Main::handleLeaderboard);
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("Server running at http://localhost:" + PORT);
    }

    private static void initDatabase() throws SQLException {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        username VARCHAR(60) PRIMARY KEY,
                        full_name VARCHAR(120) NOT NULL,
                        password_hash VARCHAR(120) NOT NULL,
                        password_salt VARCHAR(120) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS questions (
                        id INT PRIMARY KEY,
                        question_text TEXT NOT NULL,
                        options_json TEXT NOT NULL,
                        correct_option INT NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS attempts (
                        id BIGSERIAL PRIMARY KEY,
                        username VARCHAR(60) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
                        score INT NOT NULL,
                        total INT NOT NULL,
                        percentage DOUBLE PRECISION NOT NULL,
                        answers_json TEXT NOT NULL,
                        results_json TEXT NOT NULL,
                        submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        seedQuestions();
    }

    private static void seedQuestions() throws SQLException {
        List<SeedQuestion> questions = List.of(
                new SeedQuestion(1, "The set of symbols used to form strings is called", List.of("Grammar", "Alphabet", "Language", "Expression"), 1),
                new SeedQuestion(2, "A string with zero length is called", List.of("Null string", "Empty string", "Zero string", "Blank string"), 1),
                new SeedQuestion(3, "A language is defined as", List.of("Set of alphabets", "Set of strings", "Set of numbers", "Set of grammars"), 1),
                new SeedQuestion(4, "DFA stands for", List.of("Deterministic Finite Automaton", "Defined Finite Automaton", "Determined Finite Algorithm", "Defined Formal Automaton"), 0),
                new SeedQuestion(5, "Which automaton is used to recognize regular languages?", List.of("Turing Machine", "Pushdown Automaton", "Finite Automaton", "Linear Automaton"), 2),
                new SeedQuestion(6, "NFA stands for", List.of("Non-Finite Automaton", "Non-Deterministic Finite Automaton", "New Finite Automaton", "Normal Finite Automaton"), 1),
                new SeedQuestion(7, "Which symbol represents Kleene star?", List.of("+", "*", "?", "#"), 1),
                new SeedQuestion(8, "Kleene star means", List.of("One or more occurrences", "Zero or more occurrences", "Exactly one occurrence", "Exactly two occurrences"), 1),
                new SeedQuestion(9, "Which automaton uses a stack?", List.of("DFA", "NFA", "Pushdown Automaton", "Finite Automaton"), 2),
                new SeedQuestion(10, "PDA is used to recognize", List.of("Regular Language", "Context Free Language", "Recursive Language", "Unrestricted Language"), 1),
                new SeedQuestion(11, "Which machine is the most powerful computational model?", List.of("DFA", "NFA", "PDA", "Turing Machine"), 3),
                new SeedQuestion(12, "The halting problem is", List.of("Decidable", "Undecidable", "Regular", "Context Free"), 1),
                new SeedQuestion(13, "Grammar that generates context-free languages is called", List.of("Regular Grammar", "Context Free Grammar", "Linear Grammar", "Unrestricted Grammar"), 1),
                new SeedQuestion(14, "The start symbol in grammar is usually represented by", List.of("A", "S", "Z", "X"), 1),
                new SeedQuestion(15, "Which of the following is NOT a component of DFA?", List.of("Set of states", "Input alphabet", "Stack", "Transition function"), 2)
        );

        String sql = """
                INSERT INTO questions (id, question_text, options_json, correct_option)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    question_text = EXCLUDED.question_text,
                    options_json = EXCLUDED.options_json,
                    correct_option = EXCLUDED.correct_option
                """;

        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            for (SeedQuestion question : questions) {
                ps.setInt(1, question.id());
                ps.setString(2, question.question());
                ps.setString(3, GSON.toJson(question.options()));
                ps.setInt(4, question.correctOptionIndex());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        sendJson(exchange, 200, Map.of("status", "ok", "timestamp", Instant.now().toString()));
    }

    private static void handleRegister(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        RegisterRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), RegisterRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }

        if (request == null || isBlank(request.username) || isBlank(request.password) || isBlank(request.fullName)) {
            sendJson(exchange, 400, Map.of("error", "fullName, username, and password are required"));
            return;
        }

        String username = request.username.trim().toLowerCase();
        if (!username.matches("[a-z0-9_]{3,30}")) {
            sendJson(exchange, 400, Map.of("error", "Username must be 3-30 chars: letters, numbers, underscore"));
            return;
        }

        if (request.password.length() < 6) {
            sendJson(exchange, 400, Map.of("error", "Password must be at least 6 characters"));
            return;
        }

        String salt = generateSalt();
        String hash = hashPassword(request.password, salt);

        String sql = "INSERT INTO users (username, full_name, password_hash, password_salt) VALUES (?, ?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, request.fullName.trim());
            ps.setString(3, hash);
            ps.setString(4, salt);
            ps.executeUpdate();
            sendJson(exchange, 201, Map.of("success", true, "message", "Registration successful"));
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                sendJson(exchange, 409, Map.of("error", "Username already exists"));
                return;
            }
            sendJson(exchange, 500, Map.of("error", "Database error during registration"));
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        LoginRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), LoginRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }

        if (request == null || isBlank(request.username) || isBlank(request.password)) {
            sendJson(exchange, 400, Map.of("error", "username and password are required"));
            return;
        }

        String sql = "SELECT username, full_name, password_hash, password_salt FROM users WHERE username = ?";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, request.username.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    sendJson(exchange, 401, Map.of("success", false, "message", "Invalid username or password"));
                    return;
                }

                String expectedHash = rs.getString("password_hash");
                String salt = rs.getString("password_salt");
                String actualHash = hashPassword(request.password, salt);
                if (!expectedHash.equals(actualHash)) {
                    sendJson(exchange, 401, Map.of("success", false, "message", "Invalid username or password"));
                    return;
                }

                String token = UUID.randomUUID().toString();
                String username = rs.getString("username");
                SESSIONS.put(token, username);

                sendJson(exchange, 200, Map.of(
                        "success", true,
                        "token", token,
                        "username", username,
                        "fullName", rs.getString("full_name")
                ));
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error during login"));
        }
    }

    private static void handleQuestions(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String username = authenticate(exchange);
        if (username == null) {
            sendJson(exchange, 401, Map.of("error", "Unauthorized"));
            return;
        }

        List<QuestionPayload> questions = new ArrayList<>();
        String sql = "SELECT id, question_text, options_json FROM questions ORDER BY id";

        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String question = rs.getString("question_text");
                String optionsJson = rs.getString("options_json");
                List<String> options = GSON.fromJson(optionsJson, List.class);
                questions.add(new QuestionPayload(id, question, options));
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading questions"));
            return;
        }

        sendJson(exchange, 200, Map.of("questions", questions));
    }

    private static void handleSubmit(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String username = authenticate(exchange);
        if (username == null) {
            sendJson(exchange, 401, Map.of("error", "Unauthorized"));
            return;
        }

        SubmitRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), SubmitRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }

        if (request == null || request.answers == null) {
            sendJson(exchange, 400, Map.of("error", "answers array is required"));
            return;
        }

        List<QuestionRecord> questions = new ArrayList<>();
        String sql = "SELECT id, correct_option FROM questions ORDER BY id";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                questions.add(new QuestionRecord(rs.getInt("id"), rs.getInt("correct_option")));
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while validating answers"));
            return;
        }

        if (request.answers.size() != questions.size()) {
            sendJson(exchange, 400, Map.of("error", "Please submit answers for all " + questions.size() + " questions"));
            return;
        }

        int score = 0;
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            boolean isCorrect = request.answers.get(i) == questions.get(i).correctOption;
            if (isCorrect) {
                score++;
            }
            results.add(isCorrect);
        }

        int total = questions.size();
        double percentage = (score * 100.0) / total;

        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     INSERT INTO attempts (username, score, total, percentage, answers_json, results_json)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, username);
            ps.setInt(2, score);
            ps.setInt(3, total);
            ps.setDouble(4, percentage);
            ps.setString(5, GSON.toJson(request.answers));
            ps.setString(6, GSON.toJson(results));
            ps.executeUpdate();
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while saving attempt"));
            return;
        }

        sendJson(exchange, 200, Map.of(
                "username", username,
                "score", score,
                "total", total,
                "percentage", percentage,
                "results", results
        ));
    }

    private static void handleLeaderboard(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String username = authenticate(exchange);
        if (username == null) {
            sendJson(exchange, 401, Map.of("error", "Unauthorized"));
            return;
        }

        List<LeaderboardEntry> leaderboard = new ArrayList<>();
        String sql = """
                WITH ranked_attempts AS (
                    SELECT
                        a.*,
                        ROW_NUMBER() OVER (
                            PARTITION BY a.username
                            ORDER BY a.score DESC, a.percentage DESC, a.submitted_at ASC
                        ) AS rn
                    FROM attempts a
                )
                SELECT
                    u.full_name,
                    ra.username,
                    ra.score,
                    ra.total,
                    ra.percentage,
                    ra.submitted_at
                FROM ranked_attempts ra
                JOIN users u ON u.username = ra.username
                WHERE ra.rn = 1
                ORDER BY ra.score DESC, ra.percentage DESC, ra.submitted_at ASC
                LIMIT 100
                """;

        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                leaderboard.add(new LeaderboardEntry(
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getInt("score"),
                        rs.getInt("total"),
                        rs.getDouble("percentage"),
                        rs.getTimestamp("submitted_at").toInstant().toString()
                ));
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading leaderboard"));
            return;
        }

        sendJson(exchange, 200, Map.of("leaderboard", leaderboard));
    }

    private static String authenticate(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        String auth = headers.getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String token = auth.substring(7).trim();
        return SESSIONS.get(token);
    }

    private static boolean handleCors(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        handleCors(exchange);
        byte[] data = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static Connection getConnection() throws SQLException {
        String rawUrl = System.getenv("DATABASE_URL");
        String jdbcUrl = toJdbcUrl(rawUrl);
        return DriverManager.getConnection(jdbcUrl);
    }

    private static String toJdbcUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is missing. Set PostgreSQL connection string.");
        }

        if (rawUrl.startsWith("jdbc:postgresql://")) {
            return rawUrl;
        }

        if (!rawUrl.startsWith("postgres://") && !rawUrl.startsWith("postgresql://")) {
            throw new IllegalStateException("DATABASE_URL must start with postgres://, postgresql://, or jdbc:postgresql://");
        }

        URI uri = URI.create(rawUrl);
        String userInfo = uri.getUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalStateException("DATABASE_URL must include username and password");
        }

        String[] credentials = userInfo.split(":", 2);
        String username = credentials[0];
        String password = credentials[1];
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();

        return String.format("jdbc:postgresql://%s:%d/%s?user=%s&password=%s&sslmode=require",
                host,
                port,
                database,
                urlEncode(username),
                urlEncode(password));
    }

    private static String urlEncode(String value) {
        return value.replace("%", "%25")
                .replace(" ", "%20")
                .replace("@", "%40")
                .replace(":", "%3A")
                .replace("/", "%2F")
                .replace("?", "%3F")
                .replace("#", "%23")
                .replace("&", "%26")
                .replace("=", "%3D");
    }

    private static String generateSalt() {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private static String hashPassword(String password, String saltBase64) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(saltBase64));
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (Exception e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record RegisterRequest(String fullName, String username, String password) {}
    private record LoginRequest(String username, String password) {}
    private record SubmitRequest(List<Integer> answers) {}
    private record SeedQuestion(int id, String question, List<String> options, int correctOptionIndex) {}
    private record QuestionPayload(int id, String question, List<String> options) {}
    private record LeaderboardEntry(String fullName, String username, int score, int total, double percentage, String submittedAt) {}

    private static class QuestionRecord {
        final int id;
        final int correctOption;

        QuestionRecord(int id, int correctOption) {
            this.id = id;
            this.correctOption = correctOption;
        }
    }

    private static class StaticFileHandler implements HttpHandler {
        private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

        static {
            CONTENT_TYPES.put(".html", "text/html; charset=UTF-8");
            CONTENT_TYPES.put(".css", "text/css; charset=UTF-8");
            CONTENT_TYPES.put(".js", "application/javascript; charset=UTF-8");
            CONTENT_TYPES.put(".json", "application/json; charset=UTF-8");
            CONTENT_TYPES.put(".png", "image/png");
            CONTENT_TYPES.put(".jpg", "image/jpeg");
            CONTENT_TYPES.put(".svg", "image/svg+xml");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            URI uri = exchange.getRequestURI();
            String rawPath = uri.getPath();
            String path = rawPath.equals("/") ? "/index.html" : rawPath;

            Path requested = STATIC_ROOT.resolve(path.substring(1)).normalize();
            if (!requested.startsWith(STATIC_ROOT) || !Files.exists(requested) || Files.isDirectory(requested)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] data = Files.readAllBytes(requested);
            exchange.getResponseHeaders().set("Content-Type", detectContentType(requested));
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        private String detectContentType(Path file) {
            String filename = file.getFileName().toString().toLowerCase();
            for (Map.Entry<String, String> entry : CONTENT_TYPES.entrySet()) {
                if (filename.endsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return "application/octet-stream";
        }
    }
}
