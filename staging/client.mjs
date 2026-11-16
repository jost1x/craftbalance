import mineflayer from "mineflayer";

const [username, ...commands] = process.argv.slice(2);
if (!username || !/^[A-Za-z0-9_]{3,16}$/.test(username)) {
  console.error("Uso: npm run client -- <usuario> [comando-chat...]");
  process.exit(2);
}

const stayMs = Number(process.env.BOT_STAY_MS ?? 120_000);
const repeatChatMs = Number(process.env.BOT_CHAT_INTERVAL_MS ?? 0);
const bot = mineflayer.createBot({
  host: process.env.MC_HOST ?? "minecraft",
  port: Number(process.env.MC_PORT ?? 25565),
  username,
  auth: "offline",
  version: "1.21.1",
});

let repeatTimer;
let timeoutTimer;
bot.on("login", () => console.log(`LOGIN ${username}`));
bot.on("spawn", () => {
  console.log(`SPAWN ${username}`);
  for (const command of commands)
    bot.chat(command.startsWith("/") ? command : `/${command}`);
  if (repeatChatMs > 0) {
    repeatTimer = setInterval(() => bot.chat("/cb estado"), repeatChatMs);
  }
  timeoutTimer = setTimeout(() => {
    console.log(`TIMEOUT ${username}`);
    bot.quit("Staging client finished");
  }, stayMs);
});
bot.on("message", (message) => console.log(`CHAT ${message.toString()}`));
bot.on("kicked", (reason) => console.log(`KICKED ${JSON.stringify(reason)}`));
bot.on("error", (error) => console.error(`ERROR ${error.message}`));
bot.on("end", (reason) => {
  clearInterval(repeatTimer);
  clearTimeout(timeoutTimer);
  console.log(`END ${username} ${reason ?? ""}`);
});
