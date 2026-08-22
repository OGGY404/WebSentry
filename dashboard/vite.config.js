import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Calls to /api are passed to the Spring Boot backend. Doing it here means
    // the dashboard and the backend look like one address to the browser, so
    // there is no cross-origin problem while developing.
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true
      }
    }
  }
});
