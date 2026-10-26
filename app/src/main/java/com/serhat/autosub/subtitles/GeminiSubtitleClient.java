package com.serhat.autosub.subtitles;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small REST client for opt-in subtitle translation and low-confidence audio review. */
final class GeminiSubtitleClient {
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String MODEL = "gemini-3.8-flash";
    private static final int INLINE_AUDIO_LIMIT_BYTES = 14 * 1024 * 1024;

    private GeminiSubtitleClient() { }

    static String correctJapaneseClip(File wavClip, String whisperText, String apiKey,
                                      String context, String customPrompt) throws Exception {
        if (wavClip.length() > INLINE_AUDIO_LIMIT_BYTES) {
            throw new IllegalArgumentException("Audio review clip exceeds Gemini's inline audio limit");
        }
        String instructions = "Listen to this Japanese audio clip and correct only the supplied Whisper transcription. "
                + "Return only the corrected Japanese text, with no explanation, labels, timestamps, or translation. "
                + "Do not add speech that is not audible. If the Whisper text is already right, repeat it unchanged.\n"
                + "Whisper text: " + safeText(whisperText) + "\n"
                + contextBlock(context) + promptBlock(customPrompt);
        byte[] audio = readAll(wavClip);
        JSONObject audioPart = new JSONObject().put("type", "audio")
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(audio, Base64.NO_WRAP));
        String response = createInteraction(apiKey, instructions, audioPart);
        return cleanTextOutput(response);
    }

    static List<String> translateJapaneseBatch(List<String> sourceTexts, int firstId, String targetLanguage,
                                               String apiKey, String context, String customPrompt) throws Exception {
        JSONArray payload = new JSONArray();
        for (int i = 0; i < sourceTexts.size(); i++) {
            payload.put(new JSONObject().put("id", firstId + i).put("text", sourceTexts.get(i)));
        }
        String targetName = new java.util.Locale(targetLanguage).getDisplayLanguage(java.util.Locale.ENGLISH);
        if (targetName == null || targetName.isEmpty()) targetName = targetLanguage;
        String instructions = "Translate each Japanese subtitle into natural, concise " + targetName + ".\n"
                + "Preserve the meaning, speaker intent, names, and subtitle order. Return ONLY a JSON array "
                + "of objects with exactly the fields id (integer) and text (string), one for every input id, "
                + "in the original order. Never return or change timestamps. Do not merge or omit cues.\n"
                + "Subtitles: " + payload.toString() + "\n"
                + contextBlock(context) + promptBlock(customPrompt);
        String output = createInteraction(apiKey, instructions, null);
        JSONArray translated = new JSONArray(stripJsonFences(output));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < sourceTexts.size(); i++) result.add(null);
        for (int i = 0; i < translated.length(); i++) {
            JSONObject item = translated.getJSONObject(i);
            int id = item.optInt("id", -1) - firstId;
            String text = item.optString("text", "").trim();
            if (id < 0 || id >= result.size() || text.isEmpty() || result.get(id) != null) {
                throw new IllegalArgumentException("Gemini returned invalid subtitle ids or empty text");
            }
            result.set(id, text);
        }
        for (String text : result) if (text == null) throw new IllegalArgumentException("Gemini omitted one or more subtitle cues");
        return result;
    }

    private static String createInteraction(String apiKey, String textPrompt, JSONObject secondPart) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalStateException("Add a Gemini API key in Settings first");
        JSONArray input = new JSONArray().put(new JSONObject().put("type", "text").put("text", textPrompt));
        if (secondPart != null) input.put(secondPart);
        byte[] request = new JSONObject().put("model", MODEL).put("input", input)
                .toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", apiKey.trim());
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(request); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = new String(readAll(stream), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Gemini request failed (HTTP " + status + "): " + safeError(body));
            }
            JSONObject response = new JSONObject(body);
            String direct = response.optString("output_text", "").trim();
            if (!direct.isEmpty()) return direct;
            JSONArray steps = response.optJSONArray("steps");
            StringBuilder output = new StringBuilder();
            if (steps != null) for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null || !"model_output".equals(step.optString("type"))) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null && "text".equals(part.optString("type"))) {
                        if (output.length() > 0) output.append('\n');
                        output.append(part.optString("text", ""));
                    }
                }
            }
            if (output.length() == 0) throw new IllegalStateException("Gemini returned no text output");
            return output.toString().trim();
        } finally {
            connection.disconnect();
        }
    }

    private static String contextBlock(String context) {
        return context == null || context.trim().isEmpty() ? "" : "Translation and story context: " + context.trim() + "\n";
    }

    private static String promptBlock(String prompt) {
        return prompt == null || prompt.trim().isEmpty() ? "" : "User's additional instructions: " + prompt.trim() + "\n";
    }

    private static String safeText(String value) { return value == null ? "" : value.trim(); }

    private static String cleanTextOutput(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int endFence = text.lastIndexOf("```");
            if (firstLine >= 0 && endFence > firstLine) text = text.substring(firstLine + 1, endFence).trim();
        }
        return text.replaceAll("(?i)^```(?:text|plaintext)?\\s*", "").replaceAll("\\s*```$", "").trim();
    }

    private static String stripJsonFences(String value) {
        String text = cleanTextOutput(value);
        int start = text.indexOf('['), end = text.lastIndexOf(']');
        if (start >= 0 && end >= start) return text.substring(start, end + 1);
        return text;
    }

    private static String safeError(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            String message = error == null ? "" : error.optString("message", "");
            return message.isEmpty() ? "request could not be completed" : message.substring(0, Math.min(240, message.length()));
        } catch (Exception ignored) { return "request could not be completed"; }
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) { return readAll(in); }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
