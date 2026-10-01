package com.pmcl.core.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 从 GitHub 拉取全部分支的提交并排成提交图。
 * 结果写在本地缓存；再次打开只补新提交，不重下已有历史。
 * 只请求 {@code api.github.com}，不跟随重定向。
 */
public final class RepoCommitGraph {

    private static final int PAGE_SIZE = 100;
    /** 提交接口响应很大，页小一点才能在慢网络上于超时前下完。 */
    private static final int COMMIT_PAGE_SIZE = 20;
    /** 一次同步里，单条分支最多翻这么多页，剩下的下次接着拉。 */
    private static final int MAX_COMMIT_PAGES = 40;
    private static final int MAX_BRANCH_PAGES = 20;
    private static final int MAX_FILES = 40;
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final int HTTP_TIMEOUT_SECONDS = 60;
    private static final Object LOCK = new Object();
    private static final com.google.gson.reflect.TypeToken<CacheFile> CACHE_TYPE =
            new com.google.gson.reflect.TypeToken<>() {};

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private RepoCommitGraph() {}

    public static final class Commit {
        private final String sha;
        private final String subject;
        private final String body;
        private final String author;
        private final String date;
        private final List<String> parents;
        private final List<String> branches;
        private final List<String> sources;
        private final long epoch;
        private final int lane;

        Commit(String sha, String subject, String body, String author, String date,
               List<String> parents, List<String> branches, List<String> sources, long epoch, int lane) {
            this.sha = sha;
            this.subject = subject;
            this.body = body;
            this.author = author;
            this.date = date;
            this.parents = parents;
            this.branches = branches;
            this.sources = sources;
            this.epoch = epoch;
            this.lane = lane;
        }

        public String getSha() { return sha; }
        public String getSubject() { return subject; }
        public String getBody() { return body; }
        public String getAuthor() { return author; }
        public String getDate() { return date; }
        public List<String> getParents() { return parents; }
        /** 分支尖端才挂上的分支名，画在说明前面。 */
        public List<String> getBranches() { return branches; }
        /** 这次抓取里，哪些分支的历史包含这条提交。 */
        public List<String> getSources() { return sources; }
        public long getEpoch() { return epoch; }
        public int getLane() { return lane; }
    }

    public static final class Edge {
        private final int fromRow;
        private final int fromLane;
        private final int toRow;
        private final int toLane;

        Edge(int fromRow, int fromLane, int toRow, int toLane) {
            this.fromRow = fromRow;
            this.fromLane = fromLane;
            this.toRow = toRow;
            this.toLane = toLane;
        }

        public int getFromRow() { return fromRow; }
        public int getFromLane() { return fromLane; }
        public int getToRow() { return toRow; }
        public int getToLane() { return toLane; }
    }

    public static final class Graph {
        private final String defaultBranch;
        private final List<String> branchNames;
        private final List<Commit> commits;
        private final List<Edge> edges;
        private final int maxLane;

        Graph(String defaultBranch, List<String> branchNames, List<Commit> commits, List<Edge> edges, int maxLane) {
            this.defaultBranch = defaultBranch;
            this.branchNames = branchNames;
            this.commits = commits;
            this.edges = edges;
            this.maxLane = maxLane;
        }

        public String getDefaultBranch() { return defaultBranch; }
        public List<String> getBranchNames() { return branchNames; }
        public List<Commit> getCommits() { return commits; }
        public List<Edge> getEdges() { return edges; }
        public int getMaxLane() { return maxLane; }
    }

    public static final class FileChange {
        private final String path;
        private final String status;

        FileChange(String path, String status) {
            this.path = path;
            this.status = status;
        }

        public String getPath() { return path; }
        public String getStatus() { return status; }
    }

    /** 只读本地缓存。没有缓存时返回 null。 */
    public static Graph loadCached(String repo) {
        if (!GitHubReleaseSyncChecker.isValidGithubRepo(repo)) return null;
        CacheFile file;
        synchronized (LOCK) {
            file = readCache(repo.trim());
        }
        if (file.commits == null || file.commits.isEmpty()) return null;
        return layoutStored(file);
    }

    /**
     * 用本地缓存补齐仓库提交。分支尖端没变就不再请求提交列表；
     * 历史太长时记下一个起点，下次从那里继续。
     */
    public static Graph sync(String repo) throws IOException {
        if (!GitHubReleaseSyncChecker.isValidGithubRepo(repo)) throw new IOException("invalid-repo");
        String normalized = repo.trim();
        CacheFile file;
        synchronized (LOCK) {
            file = readCache(normalized);
        }
        Map<String, Node> map = nodesFrom(file);
        Map<String, String> resume = resumesFrom(file);
        String defaultBranch = file.defaultBranch == null ? "" : file.defaultBranch;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Map<String, String> tipByBranch = new LinkedHashMap<>();
        boolean listed = false;
        try {
            defaultBranch = readDefaultBranch(get(api(normalized, "")));
            List<BranchTip> tips = listBranchTips(normalized);
            if (isSafeBranch(defaultBranch)) names.add(defaultBranch);
            for (BranchTip tip : tips) {
                if (isSafeBranch(tip.name)) names.add(tip.name);
                if (isSafeBranch(tip.name) && isSafeSha(tip.sha)) tipByBranch.put(tip.name, tip.sha);
            }
            listed = true;
            for (String branch : names) {
                String tipSha = tipByBranch.getOrDefault(branch, "");
                String resumeSha = resume.getOrDefault(branch, "");
                if (isSafeSha(tipSha) && map.containsKey(tipSha) && resumeSha.isEmpty()) {
                    setTip(map, branch, tipSha);
                    tagAncestors(map, tipSha, branch);
                    continue;
                }
                if (!isSafeSha(tipSha) || !map.containsKey(tipSha)) {
                    noteWalk(resume, branch, walk(normalized, branch, branch, map, false));
                } else if (!resumeSha.isEmpty() && map.containsKey(resumeSha)) {
                    noteWalk(resume, branch, walk(normalized, branch, resumeSha, map, true));
                }
            }
            return finish(normalized, file, defaultBranch, names, tipByBranch, resume, map, true);
        } catch (IOException e) {
            if (!map.isEmpty()) {
                return finish(normalized, file, defaultBranch, names, tipByBranch, resume, map, listed);
            }
            throw classify(e);
        }
    }

    private static Graph finish(String repo, CacheFile file, String defaultBranch, LinkedHashSet<String> names,
                                Map<String, String> tipByBranch, Map<String, String> resume, Map<String, Node> map,
                                boolean listed) {
        if (listed) {
            for (Node node : map.values()) {
                node.branches.removeIf(branch -> !names.contains(branch));
                node.sources.removeIf(branch -> !names.contains(branch));
            }
            file.branchNames = new ArrayList<>(names);
            file.tips = new ArrayList<>();
            for (Map.Entry<String, String> entry : tipByBranch.entrySet()) {
                if (!names.contains(entry.getKey())) continue;
                Tip stored = new Tip();
                stored.branch = entry.getKey();
                stored.sha = entry.getValue();
                file.tips.add(stored);
            }
        }
        file.defaultBranch = defaultBranch == null ? "" : defaultBranch;
        file.resumes = new ArrayList<>();
        for (Map.Entry<String, String> entry : resume.entrySet()) {
            if (!isSafeSha(entry.getValue())) continue;
            if (listed && !names.contains(entry.getKey())) continue;
            Resume stored = new Resume();
            stored.branch = entry.getKey();
            stored.sha = entry.getValue();
            file.resumes.add(stored);
        }
        file.commits = storedFrom(map);
        persist(repo, file);
        return layoutStored(file);
    }

    /** 提交的文件列表。本地有就直接用，没有才请求 GitHub。 */
    public static List<FileChange> filesFor(String repo, String sha) throws IOException {
        if (!GitHubReleaseSyncChecker.isValidGithubRepo(repo)) throw new IOException("invalid-repo");
        if (!isSafeSha(sha)) throw new IOException("bad-sha");
        String normalized = repo.trim();
        String key = sha.trim();
        synchronized (LOCK) {
            List<FileChange> cached = filesFrom(readCache(normalized), key);
            if (cached != null) return cached;
        }
        List<FileChange> fetched = fetchFiles(normalized, key);
        synchronized (LOCK) {
            CacheFile file = readCache(normalized);
            if (file.files == null) file.files = new LinkedHashMap<>();
            List<StoredFile> stored = new ArrayList<>();
            for (FileChange change : fetched) {
                StoredFile item = new StoredFile();
                item.path = change.path;
                item.status = change.status;
                stored.add(item);
            }
            file.files.put(key, stored);
            persist(normalized, file);
        }
        return fetched;
    }

    public static List<FileChange> fetchFiles(String repo, String sha) throws IOException {
        if (!GitHubReleaseSyncChecker.isValidGithubRepo(repo)) throw new IOException("invalid-repo");
        if (!isSafeSha(sha)) throw new IOException("bad-sha");
        String json = get(api(repo.trim(), "/commits/" + sha.trim()));
        JsonObject object = objectOrNull(json);
        if (object == null || !object.has("files") || !object.get("files").isJsonArray()) return List.of();
        List<FileChange> files = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray("files")) {
            if (files.size() >= MAX_FILES) break;
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            String path = text(file, "filename");
            if (!isSafePath(path)) continue;
            String status = text(file, "status").toLowerCase(java.util.Locale.ROOT);
            if (!status.equals("added") && !status.equals("removed") && !status.equals("renamed")
                    && !status.equals("copied") && !status.equals("changed") && !status.equals("unchanged")) {
                status = "modified";
            }
            files.add(new FileChange(path, status));
        }
        return List.copyOf(files);
    }

    /** 按当前列表重新排分支线。筛选后的提交只用列表里仍在的父提交连线。 */
    public static Graph layout(String defaultBranch, List<String> branchNames, List<Commit> commits) {
        List<Commit> unique = new ArrayList<>();
        Map<String, Integer> rowOf = new LinkedHashMap<>();
        for (Commit commit : commits) {
            if (!isSafeSha(commit.sha) || rowOf.containsKey(commit.sha)) continue;
            rowOf.put(commit.sha, unique.size());
            unique.add(commit);
        }
        List<String> columns = new ArrayList<>();
        List<Commit> laidOut = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        int maxLane = 0;
        for (int row = 0; row < unique.size(); row++) {
            Commit commit = unique.get(row);
            int lane = columns.indexOf(commit.sha);
            if (lane < 0) {
                lane = firstEmpty(columns);
                if (lane < 0) {
                    columns.add(commit.sha);
                    lane = columns.size() - 1;
                } else {
                    columns.set(lane, commit.sha);
                }
            }
            maxLane = Math.max(maxLane, lane);
            List<String> parents = new ArrayList<>();
            for (String parent : commit.parents) {
                Integer parentRow = rowOf.get(parent);
                if (parentRow != null && parentRow > row && !parents.contains(parent)) parents.add(parent);
            }
            List<String> next = new ArrayList<>(columns);
            if (parents.isEmpty()) next.set(lane, "");
            for (int i = 0; i < parents.size(); i++) {
                String parent = parents.get(i);
                int existing = next.indexOf(parent);
                int target;
                if (i == 0) {
                    if (existing >= 0 && existing != lane) {
                        next.set(lane, "");
                        target = existing;
                    } else {
                        next.set(lane, parent);
                        target = lane;
                    }
                } else if (existing >= 0) {
                    target = existing;
                } else {
                    target = firstEmpty(next);
                    if (target < 0) {
                        next.add(parent);
                        target = next.size() - 1;
                    } else {
                        next.set(target, parent);
                    }
                }
                edges.add(new Edge(row, lane, rowOf.get(parent), target));
                maxLane = Math.max(maxLane, target);
            }
            columns = next;
            laidOut.add(copy(commit, commit.branches, commit.sources, lane));
        }
        return new Graph(
                defaultBranch == null ? "" : defaultBranch,
                List.copyOf(branchNames == null ? List.of() : branchNames),
                List.copyOf(laidOut),
                List.copyOf(edges),
                laidOut.isEmpty() ? 0 : maxLane);
    }

    static String readDefaultBranch(String json) throws IOException {
        JsonObject object = objectOrNull(json);
        if (object == null) throw new IOException("bad-branch");
        String branch = text(object, "default_branch");
        if (!isSafeBranch(branch)) throw new IOException("bad-branch");
        return branch;
    }

    static List<Parsed> parseCommitArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!root.isJsonArray()) return List.of();
        List<Parsed> commits = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            Parsed parsed = parseOne(element.getAsJsonObject());
            if (parsed != null) commits.add(parsed);
        }
        return commits;
    }

    private static Parsed parseOne(JsonObject object) {
        String sha = text(object, "sha");
        if (!isSafeSha(sha)) return null;
        JsonObject commit = object.has("commit") && object.get("commit").isJsonObject()
                ? object.getAsJsonObject("commit") : new JsonObject();
        String message = text(commit, "message");
        int split = message.indexOf('\n');
        String subject = (split < 0 ? message : message.substring(0, split)).trim();
        String body = split < 0 ? "" : message.substring(split + 1).trim();
        if (subject.length() > 240) subject = subject.substring(0, 240);
        if (body.length() > 2000) body = body.substring(0, 2000);
        String author = "";
        if (object.has("author") && object.get("author").isJsonObject()) {
            author = text(object.getAsJsonObject("author"), "login");
        }
        String dateRaw = "";
        if (commit.has("author") && commit.get("author").isJsonObject()) {
            JsonObject person = commit.getAsJsonObject("author");
            if (author.isEmpty()) author = text(person, "name");
            dateRaw = text(person, "date");
        }
        if (author.length() > 80) author = author.substring(0, 80);
        List<String> parents = new ArrayList<>();
        if (object.has("parents") && object.get("parents").isJsonArray()) {
            for (JsonElement parent : object.getAsJsonArray("parents")) {
                if (!parent.isJsonObject() || parents.size() >= 8) continue;
                String parentSha = text(parent.getAsJsonObject(), "sha");
                if (isSafeSha(parentSha) && !parents.contains(parentSha)) parents.add(parentSha);
            }
        }
        long epoch = 0L;
        try {
            if (!dateRaw.isEmpty()) epoch = OffsetDateTime.parse(dateRaw).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            epoch = 0L;
        }
        Commit created = new Commit(sha, subject, body, author, formatDate(dateRaw),
                List.copyOf(parents), List.of(), List.of(), epoch, 0);
        return new Parsed(created, epoch);
    }

    static List<String> readBranches(String json) {
        JsonElement root;
        try {
            root = json == null || json.isBlank() ? null : JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (root == null || !root.isJsonArray()) return List.of();
        List<String> names = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject() || names.size() >= 30) continue;
            String name = text(element.getAsJsonObject(), "name");
            if (isSafeBranch(name) && !names.contains(name)) names.add(name);
        }
        return List.copyOf(names);
    }

    static boolean isSafeBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.length() > 128) return false;
        if (branch.startsWith("/") || branch.endsWith("/") || branch.contains("//") || branch.contains("..")) {
            return false;
        }
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '/';
            if (!ok) return false;
        }
        return true;
    }

    static boolean isSafeSha(String sha) {
        if (sha == null || sha.length() < 7 || sha.length() > 64) return false;
        for (int i = 0; i < sha.length(); i++) {
            char c = sha.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static String formatDate(String published) {
        if (published == null || published.isBlank()) return "";
        try {
            var zoned = OffsetDateTime.parse(published.trim()).atZoneSameInstant(ZoneId.systemDefault());
            return zoned.getYear() + "/" + zoned.getMonthValue() + "/" + zoned.getDayOfMonth()
                    + " " + String.format("%02d:%02d", zoned.getHour(), zoned.getMinute());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isSafePath(String path) {
        if (path == null || path.isBlank() || path.length() > 512) return false;
        if (path.startsWith("/") || path.contains("\\") || path.contains("//")) return false;
        String[] parts = path.split("/", -1);
        if (parts.length > 32) return false;
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        }
        return true;
    }

    private static Commit copy(Commit commit, List<String> branches, List<String> sources) {
        return copy(commit, branches, sources, commit.lane);
    }

    private static Commit copy(Commit commit, List<String> branches, List<String> sources, int lane) {
        return new Commit(commit.sha, commit.subject, commit.body, commit.author, commit.date,
                commit.parents, List.copyOf(branches), List.copyOf(sources), commit.epoch, lane);
    }

    private static int firstEmpty(List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).isEmpty()) return i;
        }
        return -1;
    }

    private static String encodeBranch(String branch) {
        String[] parts = branch.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String api(String repo, String suffix) {
        return "https://api.github.com/repos/" + repo + suffix;
    }

    private static String get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PMCL")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("timeout", e);
        } catch (IOException e) {
            throw classify(e);
        }
        int status = resp.statusCode();
        if (status == 404) throw new IOException("not-found");
        if (status == 403 || status == 429) throw new IOException("rate-limit");
        if (status != 200) throw new IOException("http-" + status);
        String body = resp.body();
        if (body != null && body.length() > MAX_RESPONSE_CHARS) throw new IOException("too-large");
        return body == null ? "" : body;
    }

    private static IOException classify(IOException e) {
        if (e.getMessage() != null && (e.getMessage().equals("timeout")
                || e.getMessage().contains("timed out")
                || e.getMessage().contains("header parser received no bytes"))) {
            return new IOException("timeout", e);
        }
        return e;
    }

    private static JsonObject objectOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString().replace('\r', ' ').trim() : "";
    }

    static final class Parsed {
        final Commit commit;
        final long epoch;

        Parsed(Commit commit, long epoch) {
            this.commit = commit;
            this.epoch = epoch;
        }
    }

    /**
     * 把一页提交并进已有历史。碰到已经存过的提交就停下，并把该分支标到它的祖先上。
     *
     * @return true 表示这条历史已经接到缓存里的提交
     */
    static boolean absorb(Map<String, Node> map, String branch, List<Parsed> page, String skipSha, boolean markTip) {
        boolean tip = markTip;
        for (Parsed item : page) {
            String sha = item.commit.sha;
            Node existing = map.get(sha);
            if (existing != null) {
                if (sha.equals(skipSha)) {
                    addSource(existing, branch);
                    continue;
                }
                if (tip) setTip(map, branch, sha);
                tagAncestors(map, sha, branch);
                return true;
            }
            Node created = nodeFrom(item.commit);
            if (tip) {
                for (Node node : map.values()) node.branches.remove(branch);
                created.branches.add(branch);
            }
            addSource(created, branch);
            map.put(sha, created);
            tip = false;
        }
        return false;
    }

    static void tagAncestors(Map<String, Node> map, String sha, String branch) {
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(sha);
        while (!stack.isEmpty()) {
            Node node = map.get(stack.pop());
            if (node == null || node.sources.contains(branch)) continue;
            node.sources.add(branch);
            for (String parent : node.parents) stack.push(parent);
        }
    }

    private static void noteWalk(Map<String, String> resume, String branch, Walk walked) throws IOException {
        if (walked.error != null && walked.resume.isEmpty()) throw walked.error;
        if (walked.resume.isEmpty()) resume.remove(branch);
        else resume.put(branch, walked.resume);
        if (walked.error != null) throw walked.error;
    }

    /** 空的 resume 表示这条分支已经接到已知历史。出错时 resume 停在最后一页成功的提交。 */
    private static Walk walk(String repo, String branch, String start, Map<String, Node> map, boolean resume) {
        Walk result = new Walk();
        String skip = resume ? start : "";
        boolean markTip = !resume;
        try {
            for (int page = 1; page <= MAX_COMMIT_PAGES; page++) {
                String json = get(api(repo, "/commits?sha=" + encodeBranch(start)
                        + "&per_page=" + COMMIT_PAGE_SIZE + "&page=" + page));
                List<Parsed> parsed = parseCommitArray(json);
                if (parsed.isEmpty()) {
                    result.resume = "";
                    return result;
                }
                boolean connected = absorb(map, branch, parsed, skip, markTip);
                skip = "";
                markTip = false;
                if (connected || parsed.size() < COMMIT_PAGE_SIZE) {
                    result.resume = "";
                    return result;
                }
                result.resume = parsed.get(parsed.size() - 1).commit.sha;
            }
            return result;
        } catch (IOException e) {
            result.error = e;
            return result;
        }
    }

    private static final class Walk {
        String resume = "";
        IOException error;
    }

    private static List<BranchTip> listBranchTips(String repo) throws IOException {
        List<BranchTip> all = new ArrayList<>();
        for (int page = 1; page <= MAX_BRANCH_PAGES; page++) {
            String json = get(api(repo, "/branches?per_page=" + PAGE_SIZE + "&page=" + page));
            List<BranchTip> batch = readBranchTips(json);
            if (batch.isEmpty()) break;
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
        }
        return all;
    }

    static List<BranchTip> readBranchTips(String json) {
        JsonElement root;
        try {
            root = json == null || json.isBlank() ? null : JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (root == null || !root.isJsonArray()) return List.of();
        List<BranchTip> tips = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = text(object, "name");
            String sha = "";
            if (object.has("commit") && object.get("commit").isJsonObject()) {
                sha = text(object.getAsJsonObject("commit"), "sha");
            }
            if (!isSafeBranch(name)) continue;
            BranchTip tip = new BranchTip();
            tip.name = name;
            tip.sha = isSafeSha(sha) ? sha : "";
            tips.add(tip);
        }
        return tips;
    }

    private static void setTip(Map<String, Node> map, String branch, String sha) {
        for (Node node : map.values()) node.branches.remove(branch);
        Node tip = map.get(sha);
        if (tip != null && !tip.branches.contains(branch)) tip.branches.add(branch);
    }

    private static void addSource(Node node, String branch) {
        if (!node.sources.contains(branch)) node.sources.add(branch);
    }

    private static Node nodeFrom(Commit commit) {
        Node node = new Node();
        node.sha = commit.sha;
        node.subject = commit.subject;
        node.body = commit.body;
        node.author = commit.author;
        node.date = commit.date;
        node.epoch = commit.epoch;
        node.parents.addAll(commit.parents);
        return node;
    }

    private static Map<String, Node> nodesFrom(CacheFile file) {
        Map<String, Node> map = new LinkedHashMap<>();
        if (file.commits == null) return map;
        for (Stored stored : file.commits) {
            if (stored == null || !isSafeSha(stored.sha) || map.containsKey(stored.sha)) continue;
            Node node = new Node();
            node.sha = stored.sha;
            node.subject = stored.subject == null ? "" : stored.subject;
            node.body = stored.body == null ? "" : stored.body;
            node.author = stored.author == null ? "" : stored.author;
            node.date = stored.date == null ? "" : stored.date;
            node.epoch = stored.epoch;
            if (stored.parents != null) {
                for (String parent : stored.parents) {
                    if (isSafeSha(parent) && !node.parents.contains(parent)) node.parents.add(parent);
                }
            }
            if (stored.branches != null) node.branches.addAll(stored.branches);
            if (stored.sources != null) node.sources.addAll(stored.sources);
            map.put(node.sha, node);
        }
        return map;
    }

    private static Map<String, String> resumesFrom(CacheFile file) {
        Map<String, String> resume = new LinkedHashMap<>();
        if (file.resumes == null) return resume;
        for (Resume item : file.resumes) {
            if (item == null || !isSafeBranch(item.branch) || !isSafeSha(item.sha)) continue;
            resume.put(item.branch, item.sha);
        }
        return resume;
    }

    private static List<Stored> storedFrom(Map<String, Node> map) {
        List<Node> nodes = new ArrayList<>(map.values());
        nodes.sort(Comparator.comparingLong((Node node) -> node.epoch).reversed());
        List<Stored> stored = new ArrayList<>();
        for (Node node : nodes) {
            Stored item = new Stored();
            item.sha = node.sha;
            item.subject = node.subject;
            item.body = node.body;
            item.author = node.author;
            item.date = node.date;
            item.epoch = node.epoch;
            item.parents = new ArrayList<>(node.parents);
            item.branches = new ArrayList<>(node.branches);
            item.sources = new ArrayList<>(node.sources);
            stored.add(item);
        }
        return stored;
    }

    private static Graph layoutStored(CacheFile file) {
        List<Commit> commits = new ArrayList<>();
        if (file.commits != null) {
            for (Stored stored : file.commits) {
                if (stored == null || !isSafeSha(stored.sha)) continue;
                List<String> parents = new ArrayList<>();
                if (stored.parents != null) {
                    for (String parent : stored.parents) {
                        if (isSafeSha(parent)) parents.add(parent);
                    }
                }
                commits.add(new Commit(
                        stored.sha,
                        stored.subject == null ? "" : stored.subject,
                        stored.body == null ? "" : stored.body,
                        stored.author == null ? "" : stored.author,
                        stored.date == null ? "" : stored.date,
                        List.copyOf(parents),
                        List.copyOf(stored.branches == null ? List.of() : stored.branches),
                        List.copyOf(stored.sources == null ? List.of() : stored.sources),
                        stored.epoch,
                        0));
            }
        }
        commits.sort(Comparator.comparingLong(Commit::getEpoch).reversed());
        List<String> branches = file.branchNames == null ? List.of() : file.branchNames;
        return layout(file.defaultBranch == null ? "" : file.defaultBranch, branches, commits);
    }

    private static List<FileChange> filesFrom(CacheFile file, String sha) {
        if (file.files == null) return null;
        List<StoredFile> stored = file.files.get(sha);
        if (stored == null) return null;
        List<FileChange> changes = new ArrayList<>();
        for (StoredFile item : stored) {
            if (item == null || !isSafePath(item.path)) continue;
            changes.add(new FileChange(item.path, item.status == null ? "modified" : item.status));
        }
        return List.copyOf(changes);
    }

    private static CacheFile readCache(String repo) {
        CacheFile file = com.pmcl.core.cache.DataCache.load(cacheKey(repo), CACHE_TYPE);
        if (file == null) file = new CacheFile();
        if (file.commits == null) file.commits = new ArrayList<>();
        if (file.branchNames == null) file.branchNames = new ArrayList<>();
        if (file.tips == null) file.tips = new ArrayList<>();
        if (file.resumes == null) file.resumes = new ArrayList<>();
        if (file.files == null) file.files = new LinkedHashMap<>();
        if (file.defaultBranch == null) file.defaultBranch = "";
        return file;
    }

    private static void persist(String repo, CacheFile file) {
        synchronized (LOCK) {
            CacheFile disk = readCache(repo);
            if (disk.files != null) {
                if (file.files == null) file.files = new LinkedHashMap<>();
                for (Map.Entry<String, List<StoredFile>> entry : disk.files.entrySet()) {
                    file.files.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            com.pmcl.core.cache.DataCache.save(cacheKey(repo), file);
        }
    }

    private static String cacheKey(String repo) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(repo.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("gitgraph-");
            for (int i = 0; i < 8; i++) key.append(String.format("%02x", hash[i]));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "gitgraph-pmcl";
        }
    }

    static final class Node {
        String sha = "";
        String subject = "";
        String body = "";
        String author = "";
        String date = "";
        long epoch;
        final List<String> parents = new ArrayList<>();
        final List<String> branches = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
    }

    static final class BranchTip {
        String name = "";
        String sha = "";
    }

    static final class CacheFile {
        String defaultBranch = "";
        List<String> branchNames = new ArrayList<>();
        List<Tip> tips = new ArrayList<>();
        List<Resume> resumes = new ArrayList<>();
        List<Stored> commits = new ArrayList<>();
        Map<String, List<StoredFile>> files = new LinkedHashMap<>();
    }

    static final class Tip {
        String branch = "";
        String sha = "";
    }

    static final class Resume {
        String branch = "";
        String sha = "";
    }

    static final class Stored {
        String sha = "";
        String subject = "";
        String body = "";
        String author = "";
        String date = "";
        long epoch;
        List<String> parents = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        List<String> sources = new ArrayList<>();
    }

    static final class StoredFile {
        String path = "";
        String status = "";
    }
}
