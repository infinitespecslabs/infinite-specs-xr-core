import { networkInterfaces } from "node:os";
import qrcodeTerminal from "qrcode-terminal";
import type { AppConfig } from "../config.js";

export function resolveLanAddress(): string | undefined {
  const nets = networkInterfaces();
  for (const ifaces of Object.values(nets)) {
    for (const iface of ifaces ?? []) {
      if (iface.family === "IPv4" && !iface.internal) return iface.address;
    }
  }
  return undefined;
}

function truncPath(p: string, max: number): string {
  return p.length <= max ? p : "..." + p.slice(-(max - 3));
}

/** Prints the pairing banner: token, LAN URL, and a QR code encoding the
 * connection URL with the token embedded — matches even-terminal's pairing
 * story exactly (docs/SYSTEM_DESIGN.md §5.1), no separate handshake. */
export function printServerBanner(config: AppConfig): void {
  const lanIp = resolveLanAddress();
  const lines = [
    `Infinite Specs XR Daemon v0.1.0`,
    config.name ? `Name  :  ${config.name}` : "",
    `Local :  http://localhost:${config.port}`,
    lanIp ? `LAN   :  http://${lanIp}:${config.port}` : "",
    `Token :  ${config.token.slice(0, 8)}...${config.token.slice(-4)}`,
    `CWD   :  ${truncPath(config.cwd, 60)}`,
    "",
  ];
  console.log("");
  for (const line of lines) console.log(`  ${line}`);
  console.log(`  Full token: ${config.token}`);
  console.log("");

  if (!lanIp) {
    console.log("  (No LAN IPv4 address found — pairing QR code unavailable. Use the Local URL above from this machine only.)");
    return;
  }

  const params = new URLSearchParams({ token: config.token, defaultProvider: "claude" });
  if (config.name) params.set("name", config.name);
  const url = `http://${lanIp}:${config.port}?${params.toString()}`;
  console.log(`  ${url}`);
  qrcodeTerminal.generate(url, { small: true }, (qr) => {
    console.log(qr);
    console.log("");
  });
}
