import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The console talks to the server through this proxy, so no CORS setup is needed in development.
// Point it elsewhere with FERRY_API (e.g. FERRY_API=http://localhost:9000 npm run dev).
const api = process.env.FERRY_API || "http://localhost:8095";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5196,
    strictPort: true,
    proxy: { "/api": { target: api, changeOrigin: true } },
  },
});
