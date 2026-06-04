/**
 * Tavern Android Plugin Adapter
 *
 * Replaces PC git clone + npm install flow:
 *   - isomorphic-git replaces system git
 *   - Full npm (JS implementation) runs on nodejs-mobile
 *   - Common native modules loaded from APK built-in precompiled repository
 *
 * Packaged in assets/core/, loaded by server.js at startup.
 */

'use strict';

const path = require('path');
const fs = require('fs');

const BUNDLED_DEPS = process.env.TAVERN_BUNDLED_DEPS ||
    path.join(__dirname, '..', 'bundled_deps');

const BUNDLED_MODULES = {
    'marked': 'marked/marked.min.js',
    'markdown-it': 'markdown-it/index.js',
    'axios': 'axios/dist/axios.min.js',
    'lodash': 'lodash/lodash.min.js',
    'sanitize-html': 'sanitize-html/dist/sanitize-html.min.js',
    'cheerio': 'cheerio/lib/index.js',
    'better-sqlite3': 'better-sqlite3/build/Release/better_sqlite3.node',
    'sharp': 'sharp/build/Release/sharp.node',
};

let npmModule = null;

function getNpm() {
    if (npmModule) return npmModule;
    try {
        npmModule = require('npm');
    } catch (e) {
        console.error('[PluginAdapter] npm module not loaded:', e.message);
    }
    return npmModule;
}

/**
 * Install plugin
 * @param {string} repoUrl - Git repository URL
 * @param {string} targetDir - Installation target directory
 */
async function installPlugin(repoUrl, targetDir) {
    try {
        // 1. clone repository (isomorphic-git)
        console.log('[PluginAdapter] clone:', repoUrl);
        const git = require('isomorphic-git');
        const http = require('isomorphic-git/http/node');
        await git.clone({
            fs,
            http,
            dir: targetDir,
            url: repoUrl,
            singleBranch: true,
            depth: 1,
        });

        // 2. npm install
        const pkgJsonPath = path.join(targetDir, 'package.json');
        if (fs.existsSync(pkgJsonPath)) {
            console.log('[PluginAdapter] npm install');
            const npm = getNpm();
            if (npm) {
                await new Promise((resolve, reject) => {
                    npm.load({ prefix: targetDir }, (err) => {
                        if (err) return reject(err);
                        npm.commands.install([], (err, data) => {
                            if (err) return reject(err);
                            resolve(data);
                        });
                    });
                });
            }

            // 3. Fill missing .node native modules
            resolveNativeDeps(targetDir);
        }

        console.log('[PluginAdapter] Plugin installed:', targetDir);
        return { success: true, message: 'Installation successful' };
    } catch (err) {
        console.error('[PluginAdapter] Install failed:', err.message);
        return { success: false, message: err.message };
    }
}

/**
 * Fill missing native .node dependencies with built-in precompiled modules
 */
function resolveNativeDeps(pluginDir) {
    const pkgJsonPath = path.join(pluginDir, 'package.json');
    if (!fs.existsSync(pkgJsonPath)) return;

    const pkg = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'));
    const deps = pkg.dependencies || {};

    for (const [name] of Object.entries(deps)) {
        const bundledPath = BUNDLED_MODULES[name];
        if (!bundledPath) continue;

        const fullPath = path.join(BUNDLED_DEPS, bundledPath);
        if (!fs.existsSync(fullPath)) continue;

        const linkDir = path.join(pluginDir, 'node_modules', name);
        fs.mkdirSync(linkDir, { recursive: true });
        const linkTarget = path.join(linkDir, path.basename(bundledPath));

        if (!fs.existsSync(linkTarget)) {
            fs.copyFileSync(fullPath, linkTarget);
            console.log('[PluginAdapter] Native dep resolved:', name, '->', fullPath);
        }
    }
}

/**
 * List all installed plugins
 */
function listPlugins(pluginsDir) {
    if (!fs.existsSync(pluginsDir)) return [];
    return fs.readdirSync(pluginsDir).filter(name => {
        const fullPath = path.join(pluginsDir, name);
        return fs.statSync(fullPath).isDirectory();
    });
}

/**
 * Uninstall plugin
 */
function uninstallPlugin(pluginDir) {
    if (fs.existsSync(pluginDir)) {
        fs.rmSync(pluginDir, { recursive: true, force: true });
        return { success: true, message: 'Uninstalled' };
    }
    return { success: false, message: 'Plugin directory not found' };
}

module.exports = { installPlugin, listPlugins, uninstallPlugin, BUNDLED_MODULES };
