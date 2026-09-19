local resty_lock = require "resty.lock"

local _M = {}

local function entry_key(id)
    return "venue:" .. id
end

local function watermark_key(id)
    return "venue:" .. id
end

local function acquire(id, timeout)
    local lock, err = resty_lock:new("lock_dict", {
        timeout = timeout or 0,
        exptime = 1
    })
    if not lock then
        return nil, err
    end
    local elapsed, lock_err = lock:lock("venue-cache:" .. id)
    if not elapsed then
        return nil, lock_err
    end
    return lock
end

local function release(lock)
    local ok, err = lock:unlock()
    if not ok then
        ngx.log(ngx.ERR, "venue cache lock unlock failed: ", err or "unknown")
    end
end

function _M.parse_entry(raw)
    if type(raw) ~= "string" then
        return nil, nil
    end
    local separator = string.find(raw, "\n", 1, true)
    if not separator then
        return nil, nil
    end
    local version_text = string.sub(raw, 1, separator - 1)
    if not string.match(version_text, "^%d+$") then
        return nil, nil
    end
    local version = tonumber(version_text)
    local body = string.sub(raw, separator + 1)
    if not version or version < 0 or body == "" then
        return nil, nil
    end
    return version, body
end

function _M.lookup(id)
    local lock, lock_err = acquire(id, 0.05)
    if not lock then
        ngx.log(ngx.WARN, "venue cache lookup lock failed: id=", id,
                ", error=", lock_err or "unknown")
        return nil, nil, "LOCK_BUSY"
    end

    local cache = ngx.shared.venue_cache
    local versions = ngx.shared.venue_cache_version
    local raw = cache:get(entry_key(id))
    if not raw then
        release(lock)
        return nil, nil, "MISS"
    end

    local entry_version, body = _M.parse_entry(raw)
    local watermark = tonumber(versions:get(watermark_key(id))) or 0
    if not entry_version then
        cache:delete(entry_key(id))
        release(lock)
        ngx.log(ngx.WARN, "malformed venue cache entry removed: id=", id)
        return nil, nil, "MALFORMED"
    end
    if entry_version < watermark then
        cache:delete(entry_key(id))
        release(lock)
        ngx.log(ngx.WARN, "stale venue cache entry rejected: id=", id,
                ", entryVersion=", entry_version, ", watermark=", watermark)
        return nil, nil, "STALE"
    end

    release(lock)
    return body, entry_version, "HIT"
end

-- Called from body_filter, where yielding is forbidden. A contended lock therefore
-- skips this optional cache fill instead of waiting and risking a stale write.
function _M.store(id, version, body, ttl)
    local lock, lock_err = acquire(id, 0)
    if not lock then
        ngx.log(ngx.NOTICE, "venue cache fill skipped because lock is busy: id=", id,
                ", version=", version, ", error=", lock_err or "unknown")
        return false, "lock busy"
    end

    local cache = ngx.shared.venue_cache
    local versions = ngx.shared.venue_cache_version
    local key = watermark_key(id)
    local watermark = tonumber(versions:get(key)) or 0
    if version < watermark then
        release(lock)
        ngx.log(ngx.WARN, "stale venue response rejected: id=", id,
                ", responseVersion=", version, ", watermark=", watermark)
        return false, "stale version"
    end

    -- A newer authoritative response also advances the floor so a later old
    -- response cannot replace it while the invalidation task is still in flight.
    if version > watermark then
        local version_ok, version_err = versions:safe_set(key, version)
        if not version_ok then
            release(lock)
            ngx.log(ngx.ERR, "venue cache watermark write failed: id=", id,
                    ", version=", version, ", error=", version_err or "unknown")
            return false, version_err
        end
    end

    local ok, err, forcible = cache:set(entry_key(id), tostring(version) .. "\n" .. body, ttl)
    release(lock)
    if not ok then
        ngx.log(ngx.ERR, "venue cache entry write failed: id=", id,
                ", version=", version, ", error=", err or "unknown")
        return false, err
    end
    if forcible then
        ngx.log(ngx.WARN, "venue cache entry write evicted another entry: id=", id)
    end
    return true
end

function _M.purge(id, committed_version)
    local lock, lock_err = acquire(id, 0.1)
    if not lock then
        return nil, "lock failed: " .. (lock_err or "unknown")
    end

    local cache = ngx.shared.venue_cache
    local versions = ngx.shared.venue_cache_version
    local key = watermark_key(id)
    local current = tonumber(versions:get(key)) or 0
    local next_version = math.max(current, committed_version)
    if next_version > current then
        local ok, err = versions:safe_set(key, next_version)
        if not ok then
            cache:delete(entry_key(id))
            release(lock)
            return nil, "watermark write failed: " .. (err or "unknown")
        end
    end

    local raw = cache:get(entry_key(id))
    if raw then
        local entry_version = _M.parse_entry(raw)
        if not entry_version or entry_version < next_version then
            cache:delete(entry_key(id))
        end
    end
    release(lock)
    ngx.log(ngx.NOTICE, "venue cache purge accepted: id=", id,
            ", committedVersion=", committed_version, ", watermark=", next_version)
    return next_version
end

return _M
