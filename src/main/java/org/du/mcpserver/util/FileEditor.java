package org.du.mcpserver.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class FileEditor {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private final MinecraftServer server;

    public FileEditor(MinecraftServer server) {
        this.server = server;
    }

    public JsonObject readFile(String filePath) {
        JsonObject result = new JsonObject();

        try {
            Path path = resolvePath(filePath);
            validatePath(path);

            if (!Files.exists(path)) {
                result.addProperty("error", "文件不存在");
                result.addProperty("filePath", path.toString());
                return result;
            }

            if (!Files.isRegularFile(path)) {
                result.addProperty("error", "不是文件");
                return result;
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            long fileSize = Files.size(path);

            result.addProperty("status", "success");
            result.addProperty("filePath", path.toString());
            result.addProperty("fileSize", fileSize);
            result.addProperty("content", content);

        } catch (SecurityException e) {
            LOGGER.error("文件读取安全异常: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "访问被拒绝: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.error("文件读取失败: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "读取文件失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject writeFile(String filePath, String content) {
        JsonObject result = new JsonObject();

        try {
            Path path = resolvePath(filePath);
            validatePath(path);

            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                LOGGER.info("创建目录: {}", parentDir);
            }

            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long writtenBytes = content.getBytes(StandardCharsets.UTF_8).length;

            result.addProperty("status", "success");
            result.addProperty("filePath", path.toString());
            result.addProperty("writtenBytes", writtenBytes);

            LOGGER.info("文件写入成功: {} ({} bytes)", path, writtenBytes);

        } catch (SecurityException e) {
            LOGGER.error("文件写入安全异常: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "访问被拒绝: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.error("文件写入失败: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "写入文件失败: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("文件写入未知异常: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "写入文件失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject appendToFile(String filePath, String content) {
        JsonObject result = new JsonObject();

        try {
            Path path = resolvePath(filePath);
            validatePath(path);

            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            result.addProperty("status", "success");
            result.addProperty("filePath", path.toString());

            LOGGER.info("文件追加成功: {}", path);

        } catch (SecurityException e) {
            LOGGER.error("文件追加安全异常: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "访问被拒绝: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.error("文件追加失败: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "追加内容失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject deleteFile(String filePath) {
        JsonObject result = new JsonObject();

        try {
            Path path = resolvePath(filePath);
            validatePath(path);

            if (!Files.exists(path)) {
                result.addProperty("error", "文件不存在");
                return result;
            }

            Files.delete(path);

            result.addProperty("status", "success");
            result.addProperty("filePath", path.toString());

            LOGGER.info("文件删除成功: {}", path);

        } catch (SecurityException e) {
            LOGGER.error("文件删除安全异常: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "访问被拒绝: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.error("文件删除失败: {} - {}", filePath, e.getMessage());
            result.addProperty("error", "删除文件失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject listFiles(String directory) {
        JsonObject result = new JsonObject();

        try {
            Path path = resolvePath(directory);
            validatePath(path);

            if (!Files.exists(path)) {
                result.addProperty("error", "目录不存在");
                return result;
            }

            if (!Files.isDirectory(path)) {
                result.addProperty("error", "不是目录");
                return result;
            }

            JsonArray filesArray = new JsonArray();

            List<Path> files = Files.list(path).toList();
            for (Path p : files) {
                try {
                    JsonObject fileInfo = new JsonObject();
                    fileInfo.addProperty("name", p.getFileName().toString());
                    fileInfo.addProperty("isDirectory", Files.isDirectory(p));
                    if (!Files.isDirectory(p)) {
                        fileInfo.addProperty("size", Files.size(p));
                    }
                    filesArray.add(fileInfo);
                } catch (IOException e) {
                    JsonObject fileInfo = new JsonObject();
                    fileInfo.addProperty("name", p.getFileName().toString());
                    filesArray.add(fileInfo);
                }
            }

            result.addProperty("status", "success");
            result.addProperty("directory", path.toString());
            result.add("files", filesArray);

        } catch (SecurityException e) {
            LOGGER.error("目录列表安全异常: {} - {}", directory, e.getMessage());
            result.addProperty("error", "访问被拒绝: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.error("目录列表失败: {} - {}", directory, e.getMessage());
            result.addProperty("error", "列出目录失败: " + e.getMessage());
        }

        return result;
    }

    private Path resolvePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }

        String trimmedPath = filePath.trim();

        if (trimmedPath.contains("..")) {
            throw new SecurityException("路径不能包含 '..'");
        }

        if (trimmedPath.startsWith("/") || trimmedPath.startsWith("\\")) {
            return Paths.get(trimmedPath).normalize();
        }

        if (trimmedPath.matches("^[A-Za-z]:.*")) {
            return Paths.get(trimmedPath).normalize();
        }

        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(trimmedPath).normalize();
    }

    private void validatePath(Path path) {
        Path serverRoot = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().normalize().toAbsolutePath();
        Path resolvedPath = path.normalize().toAbsolutePath();

        if (!resolvedPath.startsWith(serverRoot)) {
            throw new SecurityException("Access denied: Path is outside server directory");
        }
    }
}