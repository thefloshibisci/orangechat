/**
 * 橘瓣 OrangeChat — 公告推送 Cloud Functions
 *
 * 功能：通过 Firebase Callable Function 鉴权校验后，向已订阅设备广播公告通知。
 *
 * 鉴权说明：
 *   - 必须通过 Firebase Auth 登录；
 *   - 且调用者具备管理员身份（Custom Claim `admin: true`，
 *     或 UID 在环境变量 `ANNOUNCEMENT_ADMIN_UIDS` 逗号分隔白名单中）。
 *
 * 接收端 App: AnnouncementMessagingService.kt
 */

const functions = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

// 初始化 Admin SDK（Cloud Functions 环境下自动鉴权）
if (admin.apps.length === 0) {
  admin.initializeApp();
}

const ALLOWED_TOPICS = new Set(["announcement"]);
const MAX_TITLE_LENGTH = 120;
const MAX_BODY_LENGTH = 2000;

/**
 * 发送公告（Firebase Callable）
 *
 * - title: 通知标题（可选，默认"橘瓣公告"，最长 120 字符）
 * - body:  通知正文（必填，非空字符串，最长 2000 字符）
 * - url:   点击跳转链接（可选，仅允许 https:// URL）
 * - topic: 推送主题，仅允许白名单主题，默认 "announcement"
 */
exports.sendAnnouncement = functions.onCall(async (request) => {
  const auth = request.auth;
  if (!auth) {
    throw new functions.HttpsError("unauthenticated", "需要登录后调用。");
  }

  const allowedUids = (process.env.ANNOUNCEMENT_ADMIN_UIDS || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  const isAdmin =
    auth.token?.admin === true ||
    (allowedUids.length > 0 && allowedUids.includes(auth.uid));
  if (!isAdmin) {
    throw new functions.HttpsError("permission-denied", "仅管理员可发送公告。");
  }

  const data = request.data || {};
  const rawTitle = data.title === undefined || data.title === null ? "橘瓣公告" : data.title;
  if (typeof rawTitle !== "string" || rawTitle.trim().length === 0 || rawTitle.trim().length > MAX_TITLE_LENGTH) {
    throw new functions.HttpsError("invalid-argument", "title 必须为 1-120 字的字符串。");
  }
  const title = rawTitle.trim();

  if (typeof data.body !== "string") {
    throw new functions.HttpsError("invalid-argument", "缺少 body（公告正文）。");
  }
  const body = data.body.trim();
  if (!body || body.length > MAX_BODY_LENGTH) {
    throw new functions.HttpsError("invalid-argument", "body 必须为 1-2000 字的非空字符串。");
  }

  const topic = data.topic === undefined || data.topic === null ? "announcement" : data.topic;
  if (typeof topic !== "string" || !ALLOWED_TOPICS.has(topic)) {
    throw new functions.HttpsError("invalid-argument", "不支持的推送主题。");
  }

  let url = null;
  if (data.url !== undefined && data.url !== null && data.url !== "") {
    if (typeof data.url !== "string") {
      throw new functions.HttpsError("invalid-argument", "url 必须为字符串。");
    }
    try {
      const parsed = new URL(data.url.trim());
      if (parsed.protocol !== "https:") {
        throw new Error("invalid protocol");
      }
      url = parsed.toString();
    } catch {
      throw new functions.HttpsError("invalid-argument", "url 必须为合法的 https:// 链接。");
    }
  }

  const message = {
    topic,
    android: {
      priority: "high",
    },
    data: {
      title,
      body,
      ...(url ? { url } : {}),
    },
  };

  try {
    const response = await admin.messaging().send(message);
    logger.info("公告推送成功", { messageId: response, uid: auth.uid, topic });
    return { success: true, messageId: response };
  } catch (error) {
    logger.error("公告推送失败", { error, uid: auth.uid, topic });
    throw new functions.HttpsError("internal", "推送失败，请查看服务端日志。");
  }
});
