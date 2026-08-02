import { describe, expect, it, vi } from "vitest";
import { createAuthMiddleware } from "./auth.js";

function createMockReqRes(overrides: { authorization?: string; token?: string } = {}) {
  const req = {
    headers: { authorization: overrides.authorization },
    query: { token: overrides.token },
    method: "GET",
    originalUrl: "/api/sessions",
    ip: "127.0.0.1",
  };
  const json = vi.fn();
  const status = vi.fn(() => ({ json }));
  const res = { status };
  const next = vi.fn();
  return { req, res, next, json, status };
}

describe("createAuthMiddleware", () => {
  const middleware = createAuthMiddleware("secret-token");

  it("allows a request with a matching Bearer header", () => {
    const { req, res, next } = createMockReqRes({ authorization: "Bearer secret-token" });
    middleware(req as never, res as never, next);
    expect(next).toHaveBeenCalledOnce();
    expect(res.status).not.toHaveBeenCalled();
  });

  it("allows a request with a matching ?token= query param", () => {
    const { req, res, next } = createMockReqRes({ token: "secret-token" });
    middleware(req as never, res as never, next);
    expect(next).toHaveBeenCalledOnce();
  });

  it("prefers the Bearer header over the query token when both are present", () => {
    const { req, res, next } = createMockReqRes({ authorization: "Bearer secret-token", token: "wrong" });
    middleware(req as never, res as never, next);
    expect(next).toHaveBeenCalledOnce();
  });

  it("rejects a request with no credentials", () => {
    const { req, res, next, status, json } = createMockReqRes();
    middleware(req as never, res as never, next);
    expect(next).not.toHaveBeenCalled();
    expect(status).toHaveBeenCalledWith(401);
    expect(json).toHaveBeenCalledWith({ error: "Unauthorized" });
  });

  it("rejects a request with the wrong Bearer token", () => {
    const { req, res, next, status } = createMockReqRes({ authorization: "Bearer wrong-token" });
    middleware(req as never, res as never, next);
    expect(next).not.toHaveBeenCalled();
    expect(status).toHaveBeenCalledWith(401);
  });

  it("rejects a request with the wrong query token", () => {
    const { req, res, next, status } = createMockReqRes({ token: "wrong-token" });
    middleware(req as never, res as never, next);
    expect(next).not.toHaveBeenCalled();
    expect(status).toHaveBeenCalledWith(401);
  });

  it("rejects a malformed Authorization header that isn't Bearer-prefixed", () => {
    const { req, res, next, status } = createMockReqRes({ authorization: "Basic secret-token" });
    middleware(req as never, res as never, next);
    expect(next).not.toHaveBeenCalled();
    expect(status).toHaveBeenCalledWith(401);
  });
});
