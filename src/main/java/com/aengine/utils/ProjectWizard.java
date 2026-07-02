package com.aengine.utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ProjectWizard {

    private ProjectWizard() {}

    /**
     * Deploys a standardized engine project structure at the specified target destination.
     * * @param targetDirectory The base parent directory on the host filesystem.
     * @param projectName     The name of the sub-folder and project instance.
     * @return The absolute path to the newly created project root directory.
     */
    public static String createProject(String targetDirectory, String projectName) {
        File parentDir = new File(targetDirectory);
        if (!parentDir.exists() || !parentDir.isDirectory()) {
            Logger.error(Logger.System.CORE, "Project deployment failed: Parent directory does not exist -> %s", targetDirectory);
            throw new IllegalArgumentException("Target parent directory is invalid.");
        }

        // 1. Establish absolute project root handle
        File projectRoot = new File(parentDir, projectName);
        if (projectRoot.exists()) {
            Logger.error(Logger.System.CORE, "Project deployment failed: Directory already exists -> %s", projectRoot.getAbsolutePath());
            throw new IllegalStateException("Project directory already exists.");
        }

        Logger.info(Logger.System.CORE, "Deploying project layout blueprint for '%s'...", projectName);

        // 2. Define standard subdirectory layout arrays
            String[] subDirectories = {
            // Source assets (Pre-processed by AssetBaker)
            "assets/src/textures",
            "assets/src/audio",
            "assets/src/models",    // Future .obj / .fbx

            // Optimized runtime files (Read by the Engine via VFS)
            "assets/baked/textures",      // .atex
            "assets/baked/audio",         // Future .ogg / .wav optimized
            "assets/baked/models",        // Future custom binary models
            "assets/baked/shaders",       // .vert / .frag

            // Data-Driven Design (Read natively as JSON/Text)
            "assets/data/prefabs",       // Serialized entity blueprints
            "assets/data/scenes",        // Serialized scene graphs
            "assets/data/scripts",       // Serialized script data

            // Infrastructure
            "config",
            "logs",
            ".aengine/cache"
        };

        // 3. Physical directory generation loop
        for (String subDir : subDirectories) {
            File dir = new File(projectRoot, subDir);
            if (!dir.mkdirs()) {
                Logger.error(Logger.System.CORE, "Hardware I/O error: Failed to allocate layout node: %s", subDir);
                throw new RuntimeException("Failed to initialize project directory infrastructure.");
            }
        }

        // 4. Generate project configuration metadata file (Manifest)
        generateProjectManifest(projectRoot, projectName);

        Logger.info(Logger.System.CORE, "Project '%s' deployed successfully at: %s", projectName, projectRoot.getAbsolutePath());
        return projectRoot.getAbsolutePath();
    }

    private static void generateProjectManifest(File projectRoot, String projectName) {
        File configFile = new File(projectRoot, "config/project.json");
        
        // Dynamic primitive JSON string template assembly
        String jsonContent = "{\n" +
                "  \"projectName\": \"" + projectName + "\",\n" +
                "  \"engineVersion\": \"1.0.0-alpha\",\n" +
                "  \"targetAPI\": \"OPENGL\"\n" +
                "}\n";

        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(jsonContent);
            Logger.info(Logger.System.CORE, "Project manifest descriptor serialized to disk.");
        } catch (IOException e) {
            Logger.error(Logger.System.CORE, "Critical I/O fault writing manifest: %s", e.getMessage());
            throw new RuntimeException("Failed to write project metadata file.", e);
        }
    }
}