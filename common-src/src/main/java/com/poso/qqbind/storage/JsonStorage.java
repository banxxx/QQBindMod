package com.poso.qqbind.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.poso.qqbind.QQBindConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 基于 JSON 文件的存储实现，实现 {@link DataStorage} 接口.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class JsonStorage implements DataStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonStorage.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // 双向索引
    private Map<String, String> gameIdToQQ = new HashMap<>();   // gameId -> qq
    private Map<String, String> qqToGameId = new HashMap<>();   // qq -> gameId
    private boolean loaded = false;

    private Path getDataPath() {
        String filePath = QQBindConfig.DATA_FILE_PATH;
        Path configDir = Paths.get("config");
        return configDir.resolve(filePath);
    }

    @Override
    public void load() {
        lock.writeLock().lock();
        try {
            Path dataFile = getDataPath();
            gameIdToQQ = new HashMap<>();
            qqToGameId = new HashMap<>();

            if (!Files.exists(dataFile)) {
                Files.createDirectories(dataFile.getParent());
                saveToFile();
                loaded = true;
                LOGGER.info("Created new binding data file: {}", dataFile);
                return;
            }

            try (Reader reader = Files.newBufferedReader(dataFile)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("bindings")) {
                    JsonObject bindingsObj = json.getAsJsonObject("bindings");
                    for (String key : bindingsObj.keySet()) {
                        String qq = bindingsObj.get(key).getAsString();
                        gameIdToQQ.put(key, qq);
                        qqToGameId.put(qq, key);
                    }
                }
                loaded = true;
                LOGGER.info("Loaded {} bindings from {}", gameIdToQQ.size(), dataFile);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load binding data", e);
            loaded = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void save(String qq, String gameId) {
        lock.writeLock().lock();
        try {
            // 如果该 gameId 已有绑定，先移除旧的 QQ 索引
            String oldQq = gameIdToQQ.get(gameId);
            if (oldQq != null) {
                qqToGameId.remove(oldQq);
            }
            // 如果该 qq 已有绑定，先移除旧的 gameId 索引
            String oldGameId = qqToGameId.get(qq);
            if (oldGameId != null) {
                gameIdToQQ.remove(oldGameId);
            }
            // 添加新绑定
            gameIdToQQ.put(gameId, qq);
            qqToGameId.put(qq, gameId);
            saveToFile();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String gameId) {
        lock.writeLock().lock();
        try {
            String qq = gameIdToQQ.remove(gameId);
            if (qq != null) {
                qqToGameId.remove(qq);
                saveToFile();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String getQQ(String gameId) {
        lock.readLock().lock();
        try {
            return gameIdToQQ.get(gameId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String getGameId(String qq) {
        lock.readLock().lock();
        try {
            return qqToGameId.get(qq);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<String, String> getAll() {
        lock.readLock().lock();
        try {
            return new HashMap<>(gameIdToQQ);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isLoaded() {
        return loaded;
    }

    private void saveToFile() {
        try {
            Path dataFile = getDataPath();
            JsonObject root = new JsonObject();
            JsonObject bindingsObj = new JsonObject();
            for (Map.Entry<String, String> entry : gameIdToQQ.entrySet()) {
                bindingsObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("bindings", bindingsObj);
            String json = gson.toJson(root);
            Files.write(dataFile, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("Failed to save binding data", e);
        }
    }
}