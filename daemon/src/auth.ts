import type { NextFunction, Request, RequestHandler, Response } from "express";
import { warnLog } from "./logger.js";

// Single global bearer token per daemon instance, accepted via either the
// Authorization header or a ?token= query param — matches even-terminal's
// own auth middleware exactly (see docs/SYSTEM_DESIGN.md §2).
export function createAuthMiddleware(token: string): RequestHandler {
  return (req: Request, res: Response, next: NextFunction) => {
    const header = req.headers.authorization;
    const queryToken = typeof req.query.token === "string" ? req.query.token : undefined;
    const provided = header?.startsWith("Bearer ") ? header.slice(7) : queryToken;
    if (provided !== token) {
      warnLog(`[auth] 401 ${req.method} ${req.originalUrl} (ip=${req.ip})`);
      res.status(401).json({ error: "Unauthorized" });
      return;
    }
    next();
  };
}
