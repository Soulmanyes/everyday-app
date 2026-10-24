// Shared behaviour for the app pages: Copy buttons, full-size screenshots and version numbers.

// A Copy button on every command block.
for (const pre of document.querySelectorAll("pre")) {
  const box = document.createElement("div");
  box.className = "cmd";
  pre.replaceWith(box);
  box.append(pre);
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "Copy";
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(pre.textContent.trim());
      button.textContent = "Copied";
    } catch {
      button.textContent = "Select it";  // no clipboard access (old browser or plain http)
    }
    setTimeout(() => { button.textContent = "Copy"; }, 2000);
  });
  box.append(button);
}

// Click a screenshot to see it full size; click again or press Esc to close.
const lightbox = document.createElement("dialog");
lightbox.className = "lightbox";
const big = document.createElement("img");
lightbox.append(big);
lightbox.addEventListener("click", () => lightbox.close());
document.body.append(lightbox);
for (const img of document.querySelectorAll("img.shot")) {
  img.addEventListener("click", () => {
    big.src = img.src;
    big.alt = img.alt;
    lightbox.showModal();
  });
}

// Version numbers. data-release="owner/repo": the latest GitHub release. data-updated="owner/repo": the
// date of the latest commit, for apps without releases. data-json="url" data-field="name": a version.json.
const show = (el, text) => { if (text) el.textContent = (el.dataset.prefix ?? "") + text; };
const getJson = url => fetch(url, { cache: "no-store" }).then(r => r.ok ? r.json() : Promise.reject(r.status));
for (const el of document.querySelectorAll("[data-release]")) {
  getJson(`https://api.github.com/repos/${el.dataset.release}/releases/latest`)
    .then(v => show(el, "v" + v.tag_name.replace(/^v/, ""))).catch(() => {});
}
for (const el of document.querySelectorAll("[data-updated]")) {
  getJson(`https://api.github.com/repos/${el.dataset.updated}/commits?per_page=1`)
    .then(c => show(el, "updated " + new Date(c[0].commit.committer.date)
      .toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }))).catch(() => {});
}
for (const el of document.querySelectorAll("[data-json]")) {
  getJson(el.dataset.json).then(v => show(el, v[el.dataset.field])).catch(() => {});
}
