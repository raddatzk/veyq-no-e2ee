const config = {
  repositoryUrl: "https://github.com/raddatzk/veyq",
  docsUrl: "https://github.com/raddatzk/veyq#documentation-map",
  sponsorUrl: "https://github.com/sponsors/raddatzk"
};

const supportedLanguages = ["de", "en"];
const defaultLanguage = "de";
const languageStorageKey = "veyq-site-language";
const themeStorageKey = "veyq-site-theme";
const themes = ["system", "light", "dark"];

const html = document.documentElement;
const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

function deriveRepositoryUrl() {
  if (config.repositoryUrl) {
    return config.repositoryUrl;
  }

  const host = window.location.hostname;
  const pathRepo = window.location.pathname.split("/").filter(Boolean)[0] || "veyq";
  if (host.endsWith(".github.io")) {
    const owner = host.replace(".github.io", "");
    return `https://github.com/${owner}/${pathRepo}`;
  }

  return config.repositoryUrl;
}

function getStoredLanguage() {
  const stored = localStorage.getItem(languageStorageKey);
  if (supportedLanguages.includes(stored)) {
    return stored;
  }

  const browserLanguage = navigator.language.slice(0, 2).toLowerCase();
  return supportedLanguages.includes(browserLanguage) ? browserLanguage : defaultLanguage;
}

function getStoredTheme() {
  const stored = localStorage.getItem(themeStorageKey);
  return themes.includes(stored) ? stored : "system";
}

function resolveTheme(theme) {
  if (theme === "system") {
    return mediaQuery.matches ? "dark" : "light";
  }

  return theme;
}

function updateResponsiveImages() {
  const language = supportedLanguages.includes(html.lang) ? html.lang : defaultLanguage;
  const theme = html.dataset.resolvedTheme || resolveTheme(getStoredTheme());
  const heroImage = document.querySelector("[data-hero-image]");
  const memoriesImage = document.querySelector("[data-memories-image]");
  const mobileImage = document.querySelector("[data-mobile-image]");
  const contextImage = document.querySelector("[data-context-image]");

  if (heroImage) {
    heroImage.src = `./assets/screenshots/${theme}-${language}.png`;
  }

  if (memoriesImage) {
    memoriesImage.src = `./assets/screenshots/memories-${theme}-${language}.png`;
  }

  if (mobileImage) {
    mobileImage.src = `./assets/screenshots/mobile-${theme}-${language}.png`;
  }

  if (contextImage) {
    contextImage.src = `./assets/screenshots/map-${theme}-${language}.png`;
  }
}

function applyTheme(theme) {
  const resolved = resolveTheme(theme);
  html.dataset.theme = theme;
  html.dataset.resolvedTheme = resolved;
  document.querySelectorAll("[data-theme-choice]").forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.themeChoice === theme));
  });
  updateResponsiveImages();
}

function getByPath(source, path) {
  return path.split(".").reduce((value, key) => value?.[key], source);
}

async function loadTranslations(language) {
  const response = await fetch(`./i18n/${language}.json`, { cache: "no-cache" });
  if (!response.ok) {
    throw new Error(`Could not load ${language} translations`);
  }

  return response.json();
}

function applyTranslations(messages) {
  document.querySelectorAll("[data-i18n]").forEach((element) => {
    const value = getByPath(messages, element.dataset.i18n);
    if (typeof value === "string") {
      element.textContent = value;
    }
  });

  document.querySelectorAll("[data-i18n-attr]").forEach((element) => {
    element.dataset.i18nAttr.split(",").forEach((binding) => {
      const [attribute, key] = binding.split(":").map((part) => part.trim());
      const value = getByPath(messages, key);
      if (attribute && typeof value === "string") {
        element.setAttribute(attribute, value);
      }
    });
  });

  document.title = messages.meta?.title || document.title;
  const description = document.querySelector('meta[name="description"]');
  if (description && messages.meta?.description) {
    description.setAttribute("content", messages.meta.description);
  }
}

async function applyLanguage(language) {
  const safeLanguage = supportedLanguages.includes(language) ? language : defaultLanguage;
  const messages = await loadTranslations(safeLanguage);
  applyTranslations(messages);
  html.lang = safeLanguage;
  localStorage.setItem(languageStorageKey, safeLanguage);
  document.querySelectorAll("[data-language]").forEach((button) => {
    button.setAttribute("aria-pressed", String(button.dataset.language === safeLanguage));
  });
  updateResponsiveImages();
}

function wireLinks() {
  const repositoryUrl = deriveRepositoryUrl();
  const docsUrl = config.docsUrl || `${repositoryUrl}#documentation-map`;
  document.querySelectorAll('[data-link="repo"]').forEach((link) => {
    link.href = repositoryUrl;
  });
  document.querySelectorAll('[data-link="docs"]').forEach((link) => {
    link.href = docsUrl;
  });
  document.querySelectorAll('[data-link="sponsor"]').forEach((link) => {
    link.href = config.sponsorUrl;
  });
}

function wireControls() {
  const settingsMenus = [...document.querySelectorAll(".settings-menu")];

  function closeSettings(menu) {
    const toggle = menu.querySelector(".settings-toggle");
    const popover = menu.querySelector(".settings-popover");
    popover.hidden = true;
    toggle.setAttribute("aria-expanded", "false");
  }

  function closeAllSettings(exceptMenu) {
    settingsMenus.forEach((menu) => {
      if (menu !== exceptMenu) {
        closeSettings(menu);
      }
    });
  }

  function closeSettingsFor(element) {
    const menu = element.closest(".settings-menu");
    if (menu) {
      closeSettings(menu);
    }
  }

  settingsMenus.forEach((menu) => {
    const toggle = menu.querySelector(".settings-toggle");
    const popover = menu.querySelector(".settings-popover");

    toggle.addEventListener("click", (event) => {
      event.stopPropagation();
      const isOpen = toggle.getAttribute("aria-expanded") === "true";
      closeAllSettings(menu);
      popover.hidden = isOpen;
      toggle.setAttribute("aria-expanded", String(!isOpen));
    });
  });

  document.addEventListener("click", (event) => {
    if (!settingsMenus.some((menu) => menu.contains(event.target))) {
      closeAllSettings();
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      const expandedToggle = document.querySelector('.settings-toggle[aria-expanded="true"]');
      closeAllSettings();
      expandedToggle?.focus();
    }
  });

  document.querySelectorAll("[data-language]").forEach((button) => {
    button.addEventListener("click", () => {
      applyLanguage(button.dataset.language)
        .catch(() => applyLanguage(defaultLanguage))
        .finally(() => closeSettingsFor(button));
    });
  });

  document.querySelectorAll("[data-theme-choice]").forEach((button) => {
    button.addEventListener("click", () => {
      const theme = button.dataset.themeChoice;
      localStorage.setItem(themeStorageKey, theme);
      applyTheme(theme);
      closeSettingsFor(button);
    });
  });

  mediaQuery.addEventListener("change", () => {
    if (getStoredTheme() === "system") {
      applyTheme("system");
    }
  });
}

wireLinks();
wireControls();
applyTheme(getStoredTheme());
applyLanguage(getStoredLanguage()).catch(() => applyLanguage(defaultLanguage));
