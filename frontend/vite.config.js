import { defineConfig } from "vite";

export default defineConfig({
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
        target: "http://127.0.0.1:8003",
        rewrite: (path) => path.replace(/^\/image-ai/, ""),
      },
    },
  },
});
