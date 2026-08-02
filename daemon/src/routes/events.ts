import { Router } from "express";
import { registerClient } from "../events/bus.js";

const router = Router();

router.get("/events", (req, res) => {
  const sessionId = req.query.sessionId as string | undefined;
  if (!sessionId) {
    res.status(400).json({ error: "Missing 'sessionId' query parameter" });
    return;
  }
  registerClient(sessionId, res, req.query.needReplay === "true");
});

export default router;
