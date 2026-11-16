package dev.yujian.mergeguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mergeguard")
public class MergeGuardProperties {
    private String apiKey = "";
    private String webhookSecret = "";
    private Github github = new Github();
    private Llm llm = new Llm();
    private Poll poll = new Poll();
    private Review review = new Review();

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Github getGithub() { return github; }
    public void setGithub(Github github) { this.github = github; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Poll getPoll() { return poll; }
    public void setPoll(Poll poll) { this.poll = poll; }
    public Review getReview() { return review; }
    public void setReview(Review review) { this.review = review; }

    public static class Github {
        private String token = "";
        private String apiUrl = "https://api.github.com";
        private int timeoutSeconds = 15;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Llm {
        private boolean enabled = true;
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private int maxDiffChars = 14000;
        private int timeoutSeconds = 60;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxDiffChars() { return maxDiffChars; }
        public void setMaxDiffChars(int maxDiffChars) { this.maxDiffChars = maxDiffChars; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Poll {
        private boolean enabled = false;
        private long intervalMs = 300_000L;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    }

    public static class Review {
        private int maxFiles = 60;
        private int maxFindings = 40;
        public int getMaxFiles() { return maxFiles; }
        public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
        public int getMaxFindings() { return maxFindings; }
        public void setMaxFindings(int maxFindings) { this.maxFindings = maxFindings; }
    }
}
