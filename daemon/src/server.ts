import cors from "cors";
import express, { type Express, type NextFunction, type Request, type Response } from "express";
import type { AppConfig } from "./config.js";
import { createAuthMiddleware } from "./auth.js";
import { createClaudeProvider } from "./claude/provider.js";
import { emit } from "./events/bus.js";
import eventsRouter from "./routes/events.js";
import { createCoreRouter } from "./routes/core.js";
import { configureLogger, log } from "./logger.js";
import { printServerBanner } from "./startup/banner.js";

export function buildApp(config: AppConfig): Express {
  const app = express();
  app.use(cors());
  if (config.verbose) {
    app.use((req: Request, res: Response, next: NextFunction) => {
      const startedAt = process.hrtime.bigint();
      res.on("finish", () => {
        const durationMs = Number(process.hrtime.bigint() - startedAt) / 1_000_000;
        log(`[${req.ip}] ${res.statusCode} ${req.method} ${req.originalUrl} ${durationMs.toFixed(1)}ms`);
      });
      next();
    });
  }
  app.use(express.json({ limit: "10mb" }));

  const auth = createAuthMiddleware(config.token);
  const provider = createClaudeProvider(emit);
  app.use("/api", auth, eventsRouter);
  app.use("/api", auth, createCoreRouter(provider));

  return app;
}

export function startServer(config: AppConfig): void {
  configureLogger({ verbose: config.verbose, logFile: config.logFile });
  const app = buildApp(config);
  app.listen(config.port, "0.0.0.0", () => {
    printServerBanner(config);
  });

  process.on("uncaughtException", (err) => {
    console.error(`[server] UNCAUGHT EXCEPTION: ${err.message}\n${err.stack}`);
  });
  process.on("unhandledRejection", (reason) => {
    console.error(`[server] UNHANDLED REJECTION: ${reason}`);
  });
}
