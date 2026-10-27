import { useEffect, useState } from "react";

/**
 * Hash routes: #/<module>/<sub-page>, e.g. #/settings/assumptions. Every module and sub-page
 * is bookmarkable and survives a reload. Older links like #boarding still resolve.
 */
export function parseHash(hash) {
  const [module = "", sub = ""] = hash.replace(/^#\/?/, "").split("/");
  return { module, sub };
}

export function useRoute() {
  const [route, setRoute] = useState(() => parseHash(location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseHash(location.hash));
    addEventListener("hashchange", onChange);
    return () => removeEventListener("hashchange", onChange);
  }, []);
  const go = (module, sub) => { location.hash = `/${module}${sub ? `/${sub}` : ""}`; };
  return { ...route, go };
}
