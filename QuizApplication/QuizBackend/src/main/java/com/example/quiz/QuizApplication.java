package com.example.quiz;

// --- All necessary imports ---
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
// Import MongoDB annotations
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Base64; // Required for JWT key

// --- Main Application Class ---
@SpringBootApplication
public class QuizApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuizApplication.class, args);
    }
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}

// --- --- --- --- --- --- --- --- ---
// ---       DATA ENTITIES         ---
// --- (Now for MongoDB) ---
// --- --- --- --- --- --- --- --- ---

// Question is now a simple POJO (Plain Old Java Object)
// It will be embedded inside the User document
class Question {
    private String id; // Use String for MongoDB IDs, or just an index
    private String questionText;
    private String options;
    private int correctAnswerIndex;
    private String category;
    private int masteryLevel = 0;
    private long nextReviewTimestamp = 0;

    // Constructors
    public Question() {}
    
    public Question(String id, String questionText, String options, int correctAnswerIndex, String category) {
        this.id = id;
        this.questionText = questionText;
        this.options = options;
        this.correctAnswerIndex = correctAnswerIndex;
        this.category = category;
    }

    // DTO Constructor
    public Question(QuestionDTO dto, String id) {
        this.id = id;
        this.questionText = dto.getQuestionText();
        this.options = dto.getOptions();
        this.correctAnswerIndex = dto.getCorrectAnswerIndex();
        this.category = dto.getCategory();
        this.nextReviewTimestamp = Instant.now().getEpochSecond();
    }
    
    // Getters
    public String getId() { return id; }
    public String getQuestionText() { return questionText; }
    public String getOptions() { return options; }
    public int getCorrectAnswerIndex() { return correctAnswerIndex; }
    public String getCategory() { return category; }
    public int getMasteryLevel() { return masteryLevel; }
    public long getNextReviewTimestamp() { return nextReviewTimestamp; }
    
    // Setters
    public void setId(String id) { this.id = id; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public void setOptions(String options) { this.options = options; }
    public void setCorrectAnswerIndex(int correctAnswerIndex) { this.correctAnswerIndex = correctAnswerIndex; }
    public void setCategory(String category) { this.category = category; }
    public void setMasteryLevel(int masteryLevel) { this.masteryLevel = masteryLevel; }
    public void setNextReviewTimestamp(long nextReviewTimestamp) { this.nextReviewTimestamp = nextReviewTimestamp; }
    
    public void updateMastery(boolean isCorrect) {
        if (isCorrect) {
            masteryLevel++;
            if (masteryLevel > 2) masteryLevel = 2; // Cap at "Mastered"
            switch (masteryLevel) {
                case 1: this.nextReviewTimestamp = Instant.now().plus(1, ChronoUnit.DAYS).getEpochSecond(); break;
                case 2: this.nextReviewTimestamp = Instant.now().plus(7, ChronoUnit.DAYS).getEpochSecond(); break;
            }
        } else {
            masteryLevel = 1; // Incorrect answer moves it back to "Learning"
            this.nextReviewTimestamp = Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond(); // Review soon
        }
    }
}

// User is now a MongoDB @Document
@Document(collection = "users") // This maps to a MongoDB collection
class User {
    @MongoId // This is the primary key in MongoDB
    private String id;
    @org.springframework.data.mongodb.core.index.Indexed(unique = true) // Use MongoDB's indexed
    private String username;
    private String password;

    // We embed the list of questions directly into the user
    private List<Question> questions = new ArrayList<>();

    public User() {}
    
    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }
    
    // Getters
    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public List<Question> getQuestions() { return questions; }
    
    // Setters
    public void setId(String id) { this.id = id; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
    public void setQuestions(List<Question> questions) { this.questions = questions; }
}

// --- --- --- --- --- --- --- --- ---
// ---     DATA REPOSITORIES       ---
// --- (Now for MongoDB) ---
// --- --- --- --- --- --- --- --- ---

@Repository
interface UserRepository extends MongoRepository<User, String> { // <-- This is the fix: MongoRepository
    Optional<User> findByUsername(String username);
}

// NOTE: We no longer need a QuestionRepository,
// because questions are saved inside the User.

// --- --- --- --- --- --- --- --- ---
// ---   SECURITY CONFIGURATION    ---
// --- (This is mostly the same) ---
// --- --- --- --- --- --- --- --- ---

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Autowired
    private JwtTokenFilter jwtTokenFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

@Service
class CustomUserDetailsService implements UserDetailsService {
    @Autowired
    private UserRepository userRepository; // <-- Spring will now inject the MongoRepository

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new org.springframework.security.core.userdetails.User(user.getUsername(), user.getPassword(), new ArrayList<>());
    }
}

// --- JWT (Token) Utilities ---
@Component
class JwtTokenProvider {
    // Using the static, hard-coded key to fix the login error
    private static final String SECRET_STRING = "84356789hgfds87hgf89d7hgf8d97hg8fd9h7gf8d9h7gfd89h7gfd8h7gfd89h7gfd89hgf8d9h7fd";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET_STRING));
    private static final long EXPIRATION_TIME = 86400000; // 24 hours

    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + EXPIRATION_TIME);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(SECRET_KEY)
                .compact();
    }
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claims.getSubject();
    }
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(SECRET_KEY).build().parseClaimsJws(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}

@Component
class JwtTokenFilter extends OncePerRequestFilter {
    @Autowired
    private JwtTokenProvider tokenProvider;
    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);
            if (jwt != null && tokenProvider.validateToken(jwt)) {
                String username = tokenProvider.getUsernameFromToken(jwt);
                UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }
        filterChain.doFilter(request, response);
    }
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

// --- --- --- --- --- --- --- --- ---
// ---   SERVICES & CONTROLLERS    ---
// --- (Rewritten for MongoDB) ---
// --- --- --- --- --- --- --- --- ---

@Data @AllArgsConstructor @NoArgsConstructor 
class AuthRequest { 
    private String username; 
    private String password; 
    
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
}
@Data 
@AllArgsConstructor 
class AuthResponse { 
    private String token; 
    
    public AuthResponse() {}
    
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}

@Data 
@NoArgsConstructor 
@AllArgsConstructor
class QuestionDTO {
    private String questionText;
    private String options;
    private int correctAnswerIndex;
    private String category;
    
    public String getQuestionText() { return questionText; }
    public String getOptions() { return options; }
    public int getCorrectAnswerIndex() { return correctAnswerIndex; }
    public String getCategory() { return category; }
    
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public void setOptions(String options) { this.options = options; }
    public void setCorrectAnswerIndex(int correctAnswerIndex) { this.correctAnswerIndex = correctAnswerIndex; }
    public void setCategory(String category) { this.category = category; }
}

@Service
class UserService {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ResourceLoader resourceLoader; // To load questions.json

    public User registerUser(String username, String password) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        User user = new User(username, passwordEncoder.encode(password));
        
        // --- Load questions from questions.json ---
        System.out.println("Loading master questions from questions.json for user: " + username);
        try {
            // Find the questions.json file in the 'resources' folder
            Resource resource = resourceLoader.getResource("classpath:questions.json");
            InputStream inputStream = resource.getInputStream();
            String jsonContent;
            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                jsonContent = FileCopyUtils.copyToString(reader);
            }
            
            // Map the JSON to our QuestionDTO list
            ObjectMapper objectMapper = new ObjectMapper();
            TypeReference<List<QuestionDTO>> typeRef = new TypeReference<>() {};
            List<QuestionDTO> questionDTOs = objectMapper.readValue(jsonContent, typeRef);

            // Convert DTOs to Question entities and add them to the user's list
            List<Question> sampleQuestions = new ArrayList<>();
            for (int i = 0; i < questionDTOs.size(); i++) {
                // Use a simple index as the 'id' for the embedded document
                sampleQuestions.add(new Question(questionDTOs.get(i), String.valueOf(i)));
            }
            
            user.setQuestions(sampleQuestions); // Embed the list
            System.out.println("Finished loading " + sampleQuestions.size() + " questions for user: " + username);

        } catch (IOException e) {
            System.err.println("CRITICAL ERROR: Could not load questions.json!");
            e.printStackTrace();
            throw new RuntimeException("Failed to load initial questions for new user.", e);
        }
        
        return userRepository.save(user); // Save the user (with all questions)
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private JwtTokenProvider tokenProvider;
    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody AuthRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword()));
        String token = tokenProvider.generateToken(authentication);
        AuthResponse response = new AuthResponse();
        response.setToken(token);
        return ResponseEntity.ok(response);
    }
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody AuthRequest registerRequest) {
        try {
            userService.registerUser(registerRequest.getUsername(), registerRequest.getPassword());
            return ResponseEntity.ok("User registered successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
}

@Service
class QuizService {
    @Autowired
    private UserRepository userRepository;

    // Helper to get the authenticated user
    private User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    public List<Question> getQuizSession(String username, String category) {
        User user = getUserByUsername(username);
        long now = Instant.now().getEpochSecond();
        
        // Filter questions directly from the user's embedded list
        List<Question> allUserQuestions = user.getQuestions();
        if (allUserQuestions == null) allUserQuestions = new ArrayList<>();

        // 1. Filter by Category (if provided)
        List<Question> filteredQuestions = allUserQuestions.stream()
            .filter(q -> category == null || category.isEmpty() || category.equals("-- All Categories --") || category.equals(q.getCategory()))
            .collect(Collectors.toList());

        // 2. Find due questions
        List<Question> dueQuestions = filteredQuestions.stream()
            .filter(q -> q.getNextReviewTimestamp() <= now)
            .sorted(Comparator.comparingLong(Question::getNextReviewTimestamp))
            .collect(Collectors.toList());

        // 3. Find other questions (not due, not mastered)
        List<Question> otherQuestions = new ArrayList<>();
        if (dueQuestions.size() < 5) {
            otherQuestions = filteredQuestions.stream()
                .filter(q -> q.getMasteryLevel() < 2 && q.getNextReviewTimestamp() > now)
                .collect(Collectors.toList());
            Collections.shuffle(otherQuestions);
        }
        
        // 4. Combine and return
        dueQuestions.addAll(otherQuestions.stream().limit(5 - dueQuestions.size()).collect(Collectors.toList()));
        Collections.shuffle(dueQuestions);
        return dueQuestions.stream().limit(5).collect(Collectors.toList());
    }

    public List<String> getUniqueCategories(String username) {
        User user = getUserByUsername(username);
        if (user.getQuestions() == null) return new ArrayList<>();
        return user.getQuestions().stream()
                .map(Question::getCategory)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
    
    public boolean processAnswer(String username, String questionId, int selectedOption) {
        User user = getUserByUsername(username);
        Question question = user.getQuestions().stream()
                .filter(q -> q.getId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Question not found or does not belong to user"));
        
        boolean isCorrect = (question.getCorrectAnswerIndex() == selectedOption);
        question.updateMastery(isCorrect);
        userRepository.save(user); // Save the entire user document with the updated question
        return isCorrect;
    }

    public Map<String, Long> getMasteryStats(String username) {
        User user = getUserByUsername(username);
        if (user.getQuestions() == null) return Map.of("Mastered", 0L, "Learning", 0L, "Unseen", 0L);
        
        // Calculate stats from the embedded list
        Map<String, Long> stats = user.getQuestions().stream()
            .collect(Collectors.groupingBy(
                q -> q.getMasteryLevel() == 0 ? "Unseen" : (q.getMasteryLevel() == 1 ? "Learning" : "Mastered"),
                Collectors.counting()
            ));
        
        // Ensure all keys exist even if count is 0
        stats.putIfAbsent("Mastered", 0L);
        stats.putIfAbsent("Learning", 0L);
        stats.putIfAbsent("Unseen", 0L);
        return stats;
    }
    
    public List<Question> getAllQuestions(String username) {
        return getUserByUsername(username).getQuestions();
    }

    public Question createQuestion(String username, QuestionDTO questionRequest) {
        User user = getUserByUsername(username);
        // Create a new question with a unique ID
        Question newQuestion = new Question(questionRequest, UUID.randomUUID().toString());
        if (user.getQuestions() == null) {
            user.setQuestions(new ArrayList<>());
        }
        user.getQuestions().add(newQuestion);
        userRepository.save(user);
        return newQuestion;
    }

    public Question updateQuestion(String username, String id, QuestionDTO questionDetails) {
        User user = getUserByUsername(username);
        Question question = user.getQuestions().stream()
                .filter(q -> q.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Question not found"));
        
        // Update the question's fields
        question.setQuestionText(questionDetails.getQuestionText());
        question.setOptions(questionDetails.getOptions());
        question.setCorrectAnswerIndex(questionDetails.getCorrectAnswerIndex());
        question.setCategory(questionDetails.getCategory());
        
        userRepository.save(user); // Save the whole user document
        return question;
    }

    public void deleteQuestion(String username, String id) {
        User user = getUserByUsername(username);
        if (user.getQuestions() == null) {
             throw new RuntimeException("Question not found");
        }
        // Find and remove the question from the embedded list
        boolean removed = user.getQuestions().removeIf(q -> q.getId().equals(id));
        if (!removed) {
            throw new RuntimeException("Question not found");
        }
        userRepository.save(user); // Save the user with the question removed
    }
}

// --- Main API Controller ---
@RestController
@RequestMapping("/api")
class QuizController {
    @Autowired
    private QuizService quizService;

    // Helper to get username from the security context
    private String getUsername(Principal principal) {
        if (principal == null) {
            throw new UsernameNotFoundException("User not authenticated");
        }
        return principal.getName();
    }

    @GetMapping("/session/start")
    public List<Question> startQuizSession(Principal principal, @RequestParam(required = false) String category) {
        return quizService.getQuizSession(getUsername(principal), category);
    }
    
    @GetMapping("/categories")
    public List<String> getCategories(Principal principal) {
        return quizService.getUniqueCategories(getUsername(principal));
    }

    // Note: questionId is now a String
    @PostMapping("/answer/{questionId}")
    public Map<String, Object> submitAnswer(Principal principal, @PathVariable String questionId, @RequestBody Map<String, Integer> payload) {
        int selectedOption = payload.get("selectedOption");
        boolean isCorrect = quizService.processAnswer(getUsername(principal), questionId, selectedOption);
        
        // Find the question again to return the correct index
        Question question = quizService.getAllQuestions(getUsername(principal)).stream()
            .filter(q -> q.getId().equals(questionId))
            .findFirst().get(); // Safe to .get() because processAnswer would have thrown an error
            
        return Map.of("isCorrect", isCorrect, "correctIndex", question.getCorrectAnswerIndex());
    }

    @GetMapping("/stats")
    public Map<String, Long> getStats(Principal principal) {
        return quizService.getMasteryStats(getUsername(principal));
    }

    // --- CRUD API for Question Management ---
    @GetMapping("/questions")
    public List<Question> getAllQuestions(Principal principal) {
        return quizService.getAllQuestions(getUsername(principal));
    }

    @PostMapping("/questions")
    public Question createQuestion(Principal principal, @RequestBody QuestionDTO question) {
        return quizService.createQuestion(getUsername(principal), question);
    }

    @PutMapping("/questions/{id}")
    public Question updateQuestion(Principal principal, @PathVariable String id, @RequestBody QuestionDTO questionDetails) {
        return quizService.updateQuestion(getUsername(principal), id, questionDetails);
    }

    @DeleteMapping("/questions/{id}")
    public void deleteQuestion(Principal principal, @PathVariable String id) {
        quizService.deleteQuestion(getUsername(principal), id);
    }
}

