import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { NetworkInterfaceInfo } from "node:os";

const networkInterfacesMock = vi.fn();
vi.mock("node:os", () => ({
  networkInterfaces: () => networkInterfacesMock(),
}));

const qrGenerateMock = vi.fn((_url: string, _opts: unknown, cb: (qr: string) => void) => cb("QR-ART"));
vi.mock("qrcode-terminal", () => ({
  default: { generate: (...args: Parameters<typeof qrGenerateMock>) => qrGenerateMock(...args) },
}));

const { printServerBanner, resolveLanAddress, truncPath } = await import("./banner.js");

function ipv4(address: string, internal = false): NetworkInterfaceInfo {
  return { address, netmask: "255.255.255.0", family: "IPv4", mac: "00:00:00:00:00:00", internal, cidr: `${address}/24` };
}
function ipv6(address: string, internal = false): NetworkInterfaceInfo {
  return { address, netmask: "ffff:ffff:ffff:ffff::", family: "IPv6", mac: "00:00:00:00:00:00", internal, cidr: `${address}/64`, scopeid: 0 };
}

describe("truncPath", () => {
  it("leaves short paths unchanged", () => {
    expect(truncPath("/short/path", 60)).toBe("/short/path");
  });

  it("truncates long paths, keeping the tail and prefixing an ellipsis", () => {
    const long = "/very/long/path/" + "x".repeat(60);
    const result = truncPath(long, 20);
    expect(result).toHaveLength(20);
    expect(result.startsWith("...")).toBe(true);
    expect(result.endsWith(long.slice(-17))).toBe(true);
  });
});

describe("resolveLanAddress", () => {
  it("returns the first non-internal IPv4 address", () => {
    networkInterfacesMock.mockReturnValue({
      lo0: [ipv4("127.0.0.1", true)],
      en0: [ipv6("fe80::1"), ipv4("10.0.0.5")],
    });
    expect(resolveLanAddress()).toBe("10.0.0.5");
  });

  it("skips internal and IPv6 interfaces", () => {
    networkInterfacesMock.mockReturnValue({
      lo0: [ipv4("127.0.0.1", true), ipv6("::1", true)],
    });
    expect(resolveLanAddress()).toBeUndefined();
  });

  it("returns undefined when there are no interfaces at all", () => {
    networkInterfacesMock.mockReturnValue({});
    expect(resolveLanAddress()).toBeUndefined();
  });

  it("handles undefined interface arrays gracefully", () => {
    networkInterfacesMock.mockReturnValue({ en0: undefined });
    expect(resolveLanAddress()).toBeUndefined();
  });
});

describe("printServerBanner", () => {
  let consoleLogSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleLogSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    qrGenerateMock.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function loggedText(): string {
    return consoleLogSpy.mock.calls.map((call: unknown[]) => call.join(" ")).join("\n");
  }

  it("prints local URL, truncated token, and CWD", () => {
    networkInterfacesMock.mockReturnValue({});
    printServerBanner({ port: 3456, token: "abcdefgh12345678ijkl", cwd: "/project", verbose: false });

    const output = loggedText();
    expect(output).toContain("Local :  http://localhost:3456");
    expect(output).toContain("Token :  abcdefgh...ijkl");
    expect(output).toContain("CWD   :  /project");
    expect(output).toContain("Full token: abcdefgh12345678ijkl");
  });

  it("omits the Name line when no name is configured", () => {
    networkInterfacesMock.mockReturnValue({});
    printServerBanner({ port: 3456, token: "t".repeat(20), cwd: "/project", verbose: false });
    expect(loggedText()).not.toContain("Name");
  });

  it("includes the Name line when configured", () => {
    networkInterfacesMock.mockReturnValue({});
    printServerBanner({ port: 3456, token: "t".repeat(20), cwd: "/project", verbose: false, name: "My Glasses" });
    expect(loggedText()).toContain("Name  :  My Glasses");
  });

  it("prints a fallback message and skips the QR code when no LAN address is found", () => {
    networkInterfacesMock.mockReturnValue({});
    printServerBanner({ port: 3456, token: "t".repeat(20), cwd: "/project", verbose: false });

    expect(loggedText()).toContain("No LAN IPv4 address found");
    expect(qrGenerateMock).not.toHaveBeenCalled();
  });

  it("generates a QR code encoding the pairing URL when a LAN address is found", () => {
    networkInterfacesMock.mockReturnValue({ en0: [ipv4("10.0.0.5")] });
    printServerBanner({ port: 3456, token: "mytoken", cwd: "/project", verbose: false, name: "Glasses" });

    expect(qrGenerateMock).toHaveBeenCalledOnce();
    const [url] = qrGenerateMock.mock.calls[0];
    expect(url).toBe("http://10.0.0.5:3456?token=mytoken&defaultProvider=claude&name=Glasses");
  });

  it("omits the name query param from the pairing URL when unset", () => {
    networkInterfacesMock.mockReturnValue({ en0: [ipv4("10.0.0.5")] });
    printServerBanner({ port: 3456, token: "mytoken", cwd: "/project", verbose: false });

    const [url] = qrGenerateMock.mock.calls[0];
    expect(url).toBe("http://10.0.0.5:3456?token=mytoken&defaultProvider=claude");
  });
});
