
(ns app.unimported
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.set :refer [union difference]]
            [clojure.string :as string]
            [app.util.file :refer [get-dir-files!]]))

(def bare-imported-pattern (re-pattern "import\\s\\\"[\\w\\/\\-\\@\\.]+\\\"\\;"))

(def from-pattern (re-pattern "\\sfrom\\s\\\"[\\w\\/\\-\\@\\.]+\\\""))

(defn guess-module! [filepath]
  (let [ts-file (str filepath ".ts")
        tsx-file (str filepath ".tsx")
        index-ts (path/join filepath "index.ts")
        index-tsx (path/join filepath "index.tsx")]
    (cond
      (and (fs/existsSync filepath) (.isFile (fs/statSync filepath))) filepath
      (fs/existsSync ts-file) ts-file
      (fs/existsSync tsx-file) tsx-file
      (fs/existsSync index-ts) index-ts
      (fs/existsSync index-tsx) index-tsx
      :else nil)))

(def require-pattern (re-pattern "require\\(\\\"[\\w\\/\\-\\@\\.]+\\\"\\)"))

(defn show-relative [x] (path/relative js/process.env.PWD x))

(defn analyze-file! [entry options *all-files]
  (comment println "reading entry:" entry)
  (swap! *all-files conj entry)
  (let [content (fs/readFileSync entry "utf8")
        imported-paths (->> (re-seq from-pattern content)
                            (map (fn [line] (subs line 7 (dec (count line)))))
                            (set))
        required-paths (->> (re-seq require-pattern content)
                            (map (fn [line] (subs line 9 (- (count line) 2))))
                            (set))
        bare-imported-path (->> (re-seq bare-imported-pattern content)
                                (map (fn [line] (subs line 8 (- (count line) 2))))
                                (set))]
    (comment
     println
     "required path"
     (show-relative entry)
     :required-paths
     :imported-paths
     bare-imported-path)
    (doall
     (->> (union imported-paths required-paths bare-imported-path)
          (filter
           (fn [pkg] (not (some (fn [x] (string/starts-with? pkg x)) (:packages options)))))
          (map
           (fn [file]
             (if (string/starts-with? file ".")
               (path/join entry "../" file)
               (path/join js/process.env.PWD (:base-url options) file))))
          (map
           (fn [filepath]
             (let [module-file (guess-module! filepath)]
               (comment println "is a module" module-file)
               (if (some? module-file)
                 (if (contains? @*all-files module-file)
                   (do)
                   (analyze-file! module-file options *all-files))
                 (println "no File" filepath)))))))))

(defn lookup-file! []
  (let [entry-path (aget js/process.argv 2)]
    (when (nil? entry-path) (println "No entry file!") (js/process.exit 1)))
  (let [pkgs (js->clj (js/JSON.parse (fs/readFileSync "package.json" "utf8")))
        tsconfig (js->clj (js/JSON.parse (fs/readFileSync "tsconfig.json" "utf8")))
        installed-pkgs (union
                        (set (map first (get pkgs "dependencies")))
                        (set (map first (get pkgs "devDependencies"))))
        base-url (get-in tsconfig ["compilerOptions" "baseUrl"])
        options {:base-url base-url, :packages installed-pkgs}
        entry (path/join js/process.env.PWD (aget js/process.argv 2))
        *all-modules (atom #{})
        all-files (get-dir-files! (path/join js/process.env.PWD "src/"))]
    (println "Got entry file:" (show-relative entry))
    (println "Scanning files inside src/")
    (println "Listing files not imported by" (show-relative entry) ".........")
    (println)
    (analyze-file! entry options *all-modules)
    (comment println "all modules" (pr-str all-files) (pr-str @*all-modules))
    (println
     (->> (difference all-files @*all-modules)
          (map (fn [filepath] (path/relative js/process.env.PWD filepath)))
          (sort)
          (string/join "\n")))))

(defn main! []
  (js/console.clear)
  (js/console.log)
  (js/console.log "~~~~~~~ runinng ~~~~~~~")
  (js/console.log)
  (lookup-file!))
