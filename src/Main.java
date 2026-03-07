import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class Main {
    private static final Gson GSON = new Gson();
    private static final TypeToken<List<String>> STRING_LIST_TYPE = new TypeToken<>() {};
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
        server.createContext("/api/settings", Main::handleSettings);
        server.createContext("/api/me", Main::handleMe);
        server.createContext("/api/me/attempts", Main::handleMyAttempts);
        server.createContext("/api/admin/users", Main::handleAdminUsers);
        server.createContext("/api/admin/users/manage", Main::handleAdminUserManage);
        server.createContext("/api/admin/attempts", Main::handleAdminAttempts);
        server.createContext("/api/admin/attempts/reset", Main::handleAdminAttemptReset);
        server.createContext("/api/admin/questions", Main::handleAdminQuestions);
        server.createContext("/api/admin/settings", Main::handleAdminSettings);
        server.createContext("/api/admin/summary", Main::handleAdminSummary);
        server.createContext("/api/admin/export/users.csv", Main::handleAdminUsersCsv);
        server.createContext("/api/admin/export/attempts.csv", Main::handleAdminAttemptsCsv);
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
                        role VARCHAR(20) NOT NULL DEFAULT 'student',
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'student'");

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

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS app_settings (
                        id INT PRIMARY KEY,
                        question_time_seconds INT NOT NULL DEFAULT 30,
                        shuffle_questions BOOLEAN NOT NULL DEFAULT FALSE,
                        show_correct_answers BOOLEAN NOT NULL DEFAULT TRUE,
                        max_attempts_per_user INT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }

        seedQuestions();
        seedAdminUser();
        seedDefaultSettings();
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

    private static void seedAdminUser() throws SQLException {
        String salt = generateSalt();
        String hash = hashPassword("admin123", salt);
        String sql = """
                INSERT INTO users (username, full_name, password_hash, password_salt, role)
                VALUES ('admin', 'System Admin', ?, ?, 'admin')
                ON CONFLICT (username) DO NOTHING
                """;
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.executeUpdate();
        }
    }

    private static void seedDefaultSettings() throws SQLException {
        String sql = """
                INSERT INTO app_settings (id, question_time_seconds, shuffle_questions, show_correct_answers, max_attempts_per_user)
                VALUES (1, 30, FALSE, TRUE, 0)
                ON CONFLICT (id) DO NOTHING
                """;
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.executeUpdate();
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

        String sql = "SELECT username, full_name, password_hash, password_salt, role FROM users WHERE username = ?";
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
                        "fullName", rs.getString("full_name"),
                        "role", rs.getString("role")
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

        AppSettings settings;
        try {
            settings = getSettings();
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading settings"));
            return;
        }

        if (settings.maxAttemptsPerUser > 0) {
            try (Connection connection = getConnection();
                 PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) AS cnt FROM attempts WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt("cnt") >= settings.maxAttemptsPerUser) {
                        sendJson(exchange, 403, Map.of("error", "Max attempts reached for this quiz"));
                        return;
                    }
                }
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Database error while checking attempts"));
                return;
            }
        }

        List<QuestionPayload> questions = new ArrayList<>();
        String sql = "SELECT id, question_text, options_json FROM questions ORDER BY id";

        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String question = rs.getString("question_text");
                String optionsJson = rs.getString("options_json");
                List<String> options = parseStringList(optionsJson);
                questions.add(new QuestionPayload(id, question, options));
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading questions"));
            return;
        }

        if (settings.shuffleQuestions) {
            Collections.shuffle(questions);
        }

        sendJson(exchange, 200, Map.of(
                "questions", questions,
                "settings", Map.of(
                        "questionTimeSeconds", settings.questionTimeSeconds,
                        "shuffleQuestions", settings.shuffleQuestions,
                        "showCorrectAnswers", settings.showCorrectAnswers
                )
        ));
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

        AppSettings settings;
        try {
            settings = getSettings();
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading settings"));
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

        Map<String, Object> response = new HashMap<>();
        response.put("username", username);
        response.put("score", score);
        response.put("total", total);
        response.put("percentage", percentage);
        response.put("results", settings.showCorrectAnswers ? results : Collections.nCopies(results.size(), false));
        response.put("answersVisible", settings.showCorrectAnswers);
        sendJson(exchange, 200, response);
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

    private static void handleSettings(HttpExchange exchange) throws IOException {
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

        try {
            AppSettings settings = getSettings();
            sendJson(exchange, 200, Map.of(
                    "questionTimeSeconds", settings.questionTimeSeconds,
                    "shuffleQuestions", settings.shuffleQuestions,
                    "showCorrectAnswers", settings.showCorrectAnswers,
                    "maxAttemptsPerUser", settings.maxAttemptsPerUser
            ));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading settings"));
        }
    }

    private static void handleMe(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;

        String username = authenticate(exchange);
        if (username == null) {
            sendJson(exchange, 401, Map.of("error", "Unauthorized"));
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String sql = "SELECT username, full_name, role, created_at FROM users WHERE username = ?";
            try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendJson(exchange, 404, Map.of("error", "User not found"));
                        return;
                    }
                    sendJson(exchange, 200, Map.of(
                            "username", rs.getString("username"),
                            "fullName", rs.getString("full_name"),
                            "role", rs.getString("role"),
                            "createdAt", rs.getTimestamp("created_at").toInstant().toString()
                    ));
                    return;
                }
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Database error while loading profile"));
                return;
            }
        }

        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            UserProfileRequest request;
            try {
                request = GSON.fromJson(readBody(exchange), UserProfileRequest.class);
            } catch (JsonSyntaxException e) {
                sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
                return;
            }

            if (request == null || isBlank(request.fullName)) {
                sendJson(exchange, 400, Map.of("error", "fullName is required"));
                return;
            }

            String sql = "UPDATE users SET full_name = ? WHERE username = ?";
            try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, request.fullName.trim());
                ps.setString(2, username);
                ps.executeUpdate();
                sendJson(exchange, 200, Map.of("success", true, "message", "Profile updated"));
                return;
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Database error while updating profile"));
                return;
            }
        }

        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private static void handleMyAttempts(HttpExchange exchange) throws IOException {
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

        List<Map<String, Object>> attempts = new ArrayList<>();
        String sql = """
                SELECT id, score, total, percentage, submitted_at
                FROM attempts
                WHERE username = ?
                ORDER BY submitted_at DESC
                LIMIT 100
                """;
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    attempts.add(Map.of(
                            "id", rs.getLong("id"),
                            "score", rs.getInt("score"),
                            "total", rs.getInt("total"),
                            "percentage", rs.getDouble("percentage"),
                            "submittedAt", rs.getTimestamp("submitted_at").toInstant().toString()
                    ));
                }
            }
            sendJson(exchange, 200, Map.of("attempts", attempts));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading your attempts"));
        }
    }

    private static void handleAdminUsers(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        List<Map<String, Object>> users = new ArrayList<>();
        String sql = """
                SELECT username, full_name, role, created_at
                FROM users
                ORDER BY created_at DESC
                """;
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(Map.of(
                        "username", rs.getString("username"),
                        "fullName", rs.getString("full_name"),
                        "role", rs.getString("role"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString()
                ));
            }
            sendJson(exchange, 200, Map.of("users", users));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading users"));
        }
    }

    private static void handleAdminUserManage(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            AdminUserRoleRequest request;
            try {
                request = GSON.fromJson(readBody(exchange), AdminUserRoleRequest.class);
            } catch (JsonSyntaxException e) {
                sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
                return;
            }

            if (request == null || isBlank(request.username) || isBlank(request.role)) {
                sendJson(exchange, 400, Map.of("error", "username and role are required"));
                return;
            }

            String role = request.role.trim().toLowerCase();
            if (!role.equals("student") && !role.equals("admin")) {
                sendJson(exchange, 400, Map.of("error", "role must be student or admin"));
                return;
            }

            if ("admin".equalsIgnoreCase(request.username)) {
                sendJson(exchange, 400, Map.of("error", "Default admin role cannot be changed"));
                return;
            }

            String sql = "UPDATE users SET role = ? WHERE username = ?";
            try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, role);
                ps.setString(2, request.username.trim().toLowerCase());
                int changed = ps.executeUpdate();
                if (changed == 0) {
                    sendJson(exchange, 404, Map.of("error", "User not found"));
                    return;
                }
                sendJson(exchange, 200, Map.of("success", true, "message", "User role updated"));
                return;
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Database error while updating user role"));
                return;
            }
        }

        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            DeleteUserRequest request;
            try {
                request = GSON.fromJson(readBody(exchange), DeleteUserRequest.class);
            } catch (JsonSyntaxException e) {
                sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
                return;
            }

            if (request == null || isBlank(request.username)) {
                sendJson(exchange, 400, Map.of("error", "username is required"));
                return;
            }

            String target = request.username.trim().toLowerCase();
            if ("admin".equals(target)) {
                sendJson(exchange, 400, Map.of("error", "Default admin cannot be deleted"));
                return;
            }

            String sql = "DELETE FROM users WHERE username = ?";
            try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, target);
                int changed = ps.executeUpdate();
                if (changed == 0) {
                    sendJson(exchange, 404, Map.of("error", "User not found"));
                    return;
                }
                sendJson(exchange, 200, Map.of("success", true, "message", "User deleted"));
                return;
            } catch (SQLException e) {
                sendJson(exchange, 500, Map.of("error", "Database error while deleting user"));
                return;
            }
        }

        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private static void handleAdminAttempts(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        List<Map<String, Object>> attempts = new ArrayList<>();
        String sql = """
                SELECT a.id, a.username, u.full_name, a.score, a.total, a.percentage, a.submitted_at
                FROM attempts a
                JOIN users u ON u.username = a.username
                ORDER BY a.submitted_at DESC
                LIMIT 200
                """;
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                attempts.add(Map.of(
                        "id", rs.getLong("id"),
                        "username", rs.getString("username"),
                        "fullName", rs.getString("full_name"),
                        "score", rs.getInt("score"),
                        "total", rs.getInt("total"),
                        "percentage", rs.getDouble("percentage"),
                        "submittedAt", rs.getTimestamp("submitted_at").toInstant().toString()
                ));
            }
            sendJson(exchange, 200, Map.of("attempts", attempts));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading attempts"));
        }
    }

    private static void handleAdminAttemptReset(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        ResetAttemptsRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), ResetAttemptsRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }

        if (request == null || isBlank(request.username)) {
            sendJson(exchange, 400, Map.of("error", "username is required"));
            return;
        }

        String sql = "DELETE FROM attempts WHERE username = ?";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, request.username.trim().toLowerCase());
            int deleted = ps.executeUpdate();
            sendJson(exchange, 200, Map.of("success", true, "message", "Attempts reset", "deleted", deleted));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while resetting attempts"));
        }
    }

    private static void handleAdminSummary(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        try (Connection connection = getConnection()) {
            int totalUsers = scalarInt(connection, "SELECT COUNT(*) FROM users WHERE role = 'student'");
            int totalQuestions = scalarInt(connection, "SELECT COUNT(*) FROM questions");
            int totalAttempts = scalarInt(connection, "SELECT COUNT(*) FROM attempts");
            double avgScore = scalarDouble(connection, "SELECT COALESCE(AVG(percentage), 0) FROM attempts");
            int highestScore = scalarInt(connection, "SELECT COALESCE(MAX(score), 0) FROM attempts");

            sendJson(exchange, 200, Map.of(
                    "totalUsers", totalUsers,
                    "totalQuestions", totalQuestions,
                    "totalAttempts", totalAttempts,
                    "averagePercentage", avgScore,
                    "highestScore", highestScore
            ));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while loading summary"));
        }
    }

    private static void handleAdminUsersCsv(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        StringBuilder csv = new StringBuilder();
        csv.append("full_name,username,role,created_at\n");
        String sql = "SELECT full_name, username, role, created_at FROM users ORDER BY created_at DESC";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                csv.append(csvEscape(rs.getString("full_name"))).append(",")
                        .append(csvEscape(rs.getString("username"))).append(",")
                        .append(csvEscape(rs.getString("role"))).append(",")
                        .append(csvEscape(rs.getTimestamp("created_at").toInstant().toString())).append("\n");
            }
            sendText(exchange, 200, "text/csv; charset=UTF-8", csv.toString());
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while exporting users csv"));
        }
    }

    private static void handleAdminAttemptsCsv(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        StringBuilder csv = new StringBuilder();
        csv.append("id,username,score,total,percentage,submitted_at\n");
        String sql = "SELECT id, username, score, total, percentage, submitted_at FROM attempts ORDER BY submitted_at DESC";
        try (Connection connection = getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                csv.append(rs.getLong("id")).append(",")
                        .append(csvEscape(rs.getString("username"))).append(",")
                        .append(rs.getInt("score")).append(",")
                        .append(rs.getInt("total")).append(",")
                        .append(rs.getDouble("percentage")).append(",")
                        .append(csvEscape(rs.getTimestamp("submitted_at").toInstant().toString())).append("\n");
            }
            sendText(exchange, 200, "text/csv; charset=UTF-8", csv.toString());
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while exporting attempts csv"));
        }
    }

    private static void handleAdminQuestions(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        try {
            String method = exchange.getRequestMethod().toUpperCase();
            switch (method) {
                case "GET" -> sendJson(exchange, 200, Map.of("questions", fetchQuestionsWithAnswers()));
                case "POST" -> createQuestion(exchange);
                case "PUT" -> updateQuestion(exchange);
                case "DELETE" -> deleteQuestion(exchange);
                default -> sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while handling questions"));
        }
    }

    private static void handleAdminSettings(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!isAdmin(exchange)) {
            sendJson(exchange, 403, Map.of("error", "Admin access required"));
            return;
        }

        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleSettings(exchange);
            return;
        }

        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        AdminSettingsRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), AdminSettingsRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }

        if (request == null || request.questionTimeSeconds < 5 || request.questionTimeSeconds > 180 || request.maxAttemptsPerUser < 0) {
            sendJson(exchange, 400, Map.of("error", "Invalid settings values"));
            return;
        }

        String sql = """
                UPDATE app_settings
                SET question_time_seconds = ?, shuffle_questions = ?, show_correct_answers = ?, max_attempts_per_user = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = 1
                """;
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, request.questionTimeSeconds);
            ps.setBoolean(2, request.shuffleQuestions);
            ps.setBoolean(3, request.showCorrectAnswers);
            ps.setInt(4, request.maxAttemptsPerUser);
            ps.executeUpdate();
            sendJson(exchange, 200, Map.of("success", true, "message", "Settings updated"));
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of("error", "Database error while saving settings"));
        }
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

    private static boolean isAdmin(HttpExchange exchange) {
        String username = authenticate(exchange);
        if (username == null) {
            return false;
        }
        String sql = "SELECT role FROM users WHERE username = ?";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "admin".equalsIgnoreCase(rs.getString("role"));
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static AppSettings getSettings() throws SQLException {
        String sql = "SELECT question_time_seconds, shuffle_questions, show_correct_answers, max_attempts_per_user FROM app_settings WHERE id = 1";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new AppSettings(
                        rs.getInt("question_time_seconds"),
                        rs.getBoolean("shuffle_questions"),
                        rs.getBoolean("show_correct_answers"),
                        rs.getInt("max_attempts_per_user")
                );
            }
        }
        return new AppSettings(30, false, true, 0);
    }

    private static List<String> parseStringList(String rawJson) {
        List<String> parsed = GSON.fromJson(rawJson, STRING_LIST_TYPE.getType());
        return parsed == null ? List.of() : parsed;
    }

    private static List<Map<String, Object>> fetchQuestionsWithAnswers() throws SQLException {
        List<Map<String, Object>> questions = new ArrayList<>();
        String sql = "SELECT id, question_text, options_json, correct_option FROM questions ORDER BY id";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                questions.add(Map.of(
                        "id", rs.getInt("id"),
                        "question", rs.getString("question_text"),
                        "options", parseStringList(rs.getString("options_json")),
                        "correctOptionIndex", rs.getInt("correct_option")
                ));
            }
        }
        return questions;
    }

    private static void createQuestion(HttpExchange exchange) throws IOException, SQLException {
        AdminQuestionRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), AdminQuestionRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }
        if (!isValidQuestionRequest(request)) {
            sendJson(exchange, 400, Map.of("error", "Invalid question payload"));
            return;
        }
        String sql = "INSERT INTO questions (id, question_text, options_json, correct_option) VALUES (?, ?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, request.id);
            ps.setString(2, request.question.trim());
            ps.setString(3, GSON.toJson(request.options));
            ps.setInt(4, request.correctOptionIndex);
            ps.executeUpdate();
            sendJson(exchange, 201, Map.of("success", true, "message", "Question created"));
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                sendJson(exchange, 409, Map.of("error", "Question ID already exists"));
                return;
            }
            throw e;
        }
    }

    private static void updateQuestion(HttpExchange exchange) throws IOException, SQLException {
        AdminQuestionRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), AdminQuestionRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }
        if (!isValidQuestionRequest(request)) {
            sendJson(exchange, 400, Map.of("error", "Invalid question payload"));
            return;
        }
        String sql = """
                UPDATE questions
                SET question_text = ?, options_json = ?, correct_option = ?
                WHERE id = ?
                """;
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, request.question.trim());
            ps.setString(2, GSON.toJson(request.options));
            ps.setInt(3, request.correctOptionIndex);
            ps.setInt(4, request.id);
            int changed = ps.executeUpdate();
            if (changed == 0) {
                sendJson(exchange, 404, Map.of("error", "Question not found"));
                return;
            }
            sendJson(exchange, 200, Map.of("success", true, "message", "Question updated"));
        }
    }

    private static void deleteQuestion(HttpExchange exchange) throws IOException, SQLException {
        DeleteQuestionRequest request;
        try {
            request = GSON.fromJson(readBody(exchange), DeleteQuestionRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid JSON payload"));
            return;
        }
        if (request == null || request.id < 1) {
            sendJson(exchange, 400, Map.of("error", "Valid question id is required"));
            return;
        }
        String sql = "DELETE FROM questions WHERE id = ?";
        try (Connection connection = getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, request.id);
            int changed = ps.executeUpdate();
            if (changed == 0) {
                sendJson(exchange, 404, Map.of("error", "Question not found"));
                return;
            }
            sendJson(exchange, 200, Map.of("success", true, "message", "Question deleted"));
        }
    }

    private static boolean isValidQuestionRequest(AdminQuestionRequest request) {
        if (request == null || request.id < 1 || isBlank(request.question) || request.options == null || request.options.size() < 2) {
            return false;
        }
        for (String option : request.options) {
            if (isBlank(option)) {
                return false;
            }
        }
        return request.correctOptionIndex >= 0 && request.correctOptionIndex < request.options.size();
    }

    private static boolean handleCors(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
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

    private static void sendText(HttpExchange exchange, int status, String contentType, String text) throws IOException {
        handleCors(exchange);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static double scalarDouble(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getDouble(1);
        }
    }

    private static String csvEscape(String value) {
        if (value == null) return "\"\"";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
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
    private static class AdminQuestionRequest {
        int id;
        String question;
        List<String> options;
        int correctOptionIndex;
    }
    private static class DeleteQuestionRequest {
        int id;
    }
    private static class AdminSettingsRequest {
        int questionTimeSeconds;
        boolean shuffleQuestions;
        boolean showCorrectAnswers;
        int maxAttemptsPerUser;
    }
    private static class UserProfileRequest {
        String fullName;
    }
    private static class AdminUserRoleRequest {
        String username;
        String role;
    }
    private static class DeleteUserRequest {
        String username;
    }
    private static class ResetAttemptsRequest {
        String username;
    }
    private static class AppSettings {
        final int questionTimeSeconds;
        final boolean shuffleQuestions;
        final boolean showCorrectAnswers;
        final int maxAttemptsPerUser;

        AppSettings(int questionTimeSeconds, boolean shuffleQuestions, boolean showCorrectAnswers, int maxAttemptsPerUser) {
            this.questionTimeSeconds = questionTimeSeconds;
            this.shuffleQuestions = shuffleQuestions;
            this.showCorrectAnswers = showCorrectAnswers;
            this.maxAttemptsPerUser = maxAttemptsPerUser;
        }
    }

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
