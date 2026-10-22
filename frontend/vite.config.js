import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const imageServicePort = process.env.SMARTPLAY_IMAGE_SERVICE_PORT || "8003";
const imageServiceUrl = `http://127.0.0.1:${imageServicePort}`;
const imageServiceHealthUrl = `${imageServiceUrl}/health`;
const imageServiceStartupTimeoutMs = 120_000;

function imageServicePlugin(imageApiBase) {
  let imageServiceProcess;
  let imageServiceStartError;
  let stopping = false;

  function stopImageService() {
    stopping = true;
    if (imageServiceProcess && imageServiceProcess.exitCode === null) {
      imageServiceProcess.kill();
    }
  }

  async function waitForImageService() {
    const startupDeadline = Date.now() + imageServiceStartupTimeoutMs;
    let lastError;

    while (Date.now() < startupDeadline) {
      if (imageServiceStartError) {
        throw new Error(`Failed to start the image service: ${imageServiceStartError.message}`);
      }
      if (imageServiceProcess.exitCode !== null) {
        throw new Error(
          `Image service exited during startup (code ${imageServiceProcess.exitCode}). Check the service output above.`,
        );
      }

      try {
        const response = await fetch(imageServiceHealthUrl, {
          signal: AbortSignal.timeout(2_000),
        });
        if (response.ok) {
          const health = await response.json();
          if (["running", "degraded"].includes(health.status) && health.model === "ResNet18" && health.colour_analysis_available) {
            if (health.prediction_error) console.warn(health.prediction_error);
            return;
          }
          lastError = new Error("The service health response did not identify the ResNet18 model.");
        } else {
          lastError = new Error(`The service health check returned HTTP ${response.status}.`);
        }
      } catch (error) {
        lastError = error;
      }

      await new Promise((resolveDelay) => setTimeout(resolveDelay, 500));
    }

    throw new Error(
      `Image service did not become ready within ${imageServiceStartupTimeoutMs / 1_000} seconds. ${lastError instanceof Error ? lastError.message : "Check the service output above."}`,
    );
  }

  return {
    name: "smartplay-image-service",
    apply: "serve",
    async configureServer(server) {
      if (imageApiBase !== "/image-ai") {
        return;
      }

      try {
        const existingResponse = await fetch(imageServiceHealthUrl, {
          signal: AbortSignal.timeout(1_000),
        }).catch(() => null);
        if (existingResponse?.ok) {
          const health = await existingResponse.json();
          if (["running", "degraded"].includes(health.status) && health.model === "ResNet18") {
            if (!health.colour_analysis_available) {
              throw new Error(`An older image service is running on port ${imageServicePort}. Stop that Python service and restart npm run dev to load colour analysis. You can also set SMARTPLAY_IMAGE_SERVICE_PORT to a free port.`);
            }
            if (health.prediction_error) server.config.logger.warn(health.prediction_error);
            server.config.logger.info(`Using the existing ResNet18 image service on port ${imageServicePort}.`);
            return;
          }
        }

        const pythonExecutable = process.platform === "win32"
          ? resolve(projectRoot, ".venv", "Scripts", "python.exe")
          : resolve(projectRoot, ".venv", "bin", "python");
        if (!existsSync(pythonExecutable)) {
          throw new Error(
            `Python environment not found at ${pythonExecutable}. Create the repository .venv and install ai-service requirements.`,
          );
        }

        imageServiceProcess = spawn(
          pythonExecutable,
          [
            "-m",
            "uvicorn",
            "src.image_creativity_api:app",
            "--app-dir",
            "./ai-service",
            "--host",
            "127.0.0.1",
            "--port",
            imageServicePort,
          ],
          { cwd: projectRoot, stdio: "inherit", windowsHide: true },
        );
        imageServiceProcess.once("error", (error) => {
          imageServiceStartError = error;
        });
        imageServiceProcess.once("exit", (code) => {
          if (!stopping) {
            server.config.logger.error(
              `Image service stopped unexpectedly (code ${code ?? "unknown"}).`,
            );
          }
        });

        await waitForImageService();
        server.config.logger.info(`Image service is ready on port ${imageServicePort}.`);
        server.httpServer?.once("close", stopImageService);
      } catch (error) {
        stopImageService();
        throw error;
      }
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  const imageApiBase = env.VITE_IMAGE_API_URL || "/image-ai";

  return {
    plugins: [imageServicePlugin(imageApiBase)],
    server: {
      proxy: {
        "/api": "http://localhost:8080",
        "/ai": {
          target: "http://127.0.0.1:8000",
          rewrite: (path) => path.replace(/^\/ai/, ""),
        },
        "/voice-ai": {
          target: "http://127.0.0.1:8002",
          rewrite: (path) => path.replace(/^\/voice-ai/, ""),
        },
        "/image-ai": {
          target: imageServiceUrl,
          rewrite: (path) => path.replace(/^\/image-ai/, ""),
        },
      },
    },
  };
});
