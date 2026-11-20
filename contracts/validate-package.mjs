// Independent validator for studio-production-package 2.x (JSON Schema + referential integrity).
// Does not import any app code. Usage: node contracts/validate-package.mjs package.json
import Ajv from "ajv";
import addFormats from "ajv-formats";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const schema = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), "production-package.schema.json"), "utf8"));
const ajv = new Ajv({ allErrors: true, strict: false }); addFormats(ajv);
const check = ajv.compile(schema);

export function validatePackage(pkg) {
  const errors = [];
  if (!check(pkg)) for (const e of check.errors) errors.push(`schema ${e.instancePath || "/"} ${e.message}`);
  if (errors.length) return { ok: false, errors };
  const shots = new Set(pkg.shots.map((s) => s.id)), scenes = new Set(pkg.scenes.map((s) => s.id)), takes = new Set(pkg.takes.map((t) => t.id)), media = new Set(pkg.media.map((m) => m.id));
  const ref = (set, id, where) => { if (id !== null && id !== undefined && !set.has(id)) errors.push(`dangling ${where}: ${id}`); };
  for (const s of pkg.shots) { ref(scenes, s.sceneId, `shot ${s.code}.sceneId`); for (const t of s.takeIds) ref(takes, t, `shot ${s.code}.takeIds`); ref(takes, s.preferredTakeId, `shot ${s.code}.preferredTakeId`); }
  for (const t of pkg.takes) { ref(shots, t.shotId, `take ${t.id}.shotId`); for (const m of t.mediaAssetIds) ref(media, m, `take ${t.id}.mediaAssetIds`); }
  for (const m of pkg.mediaRelink) { ref(media, m.mediaId, "mediaRelink.mediaId"); ref(takes, m.takeId, `media ${m.filename}.takeId`); ref(shots, m.shotId, `media ${m.filename}.shotId`); if (m.role === "derivative" && !m.masterId) errors.push(`derivative ${m.filename} has no master`); ref(media, m.masterId, `media ${m.filename}.masterId`); }
  for (const id of [...pkg.narrativeOrder, ...pkg.editOrder, ...(pkg.shootOrder ?? [])]) ref(shots, id, "order");
  for (const d of pkg.shootDays) for (const id of d.order) ref(shots, id, `shootDay ${d.label}.order`);
  for (const st of pkg.setups) for (const id of st.shotIds) ref(shots, id, `setup ${st.name}.shotIds`);
  for (const c of pkg.continuity) { ref(shots, c.shotId, "continuity.shotId"); ref(takes, c.takeId, "continuity.takeId"); }
  for (const f of pkg.storyboardIndex ?? []) ref(shots, f.shotId, "storyboard.shotId");
  const r = pkg.readiness; if (r.readyForEditorialHandoff.ok && !(r.preProductionComplete.ok && r.productionRecordsComplete.ok && r.actualMediaVerified.ok)) errors.push("readyForEditorialHandoff without its prerequisites");
  if (r.actualMediaVerified.ok && pkg.mediaRelink.some((m) => m.verification === "simulated")) errors.push("media marked verified while simulated recordings exist");
  return { ok: errors.length === 0, errors };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const res = validatePackage(JSON.parse(readFileSync(process.argv[2], "utf8")));
  console.log(res.ok ? "valid" : res.errors.join("\n")); process.exit(res.ok ? 0 : 1);
}
