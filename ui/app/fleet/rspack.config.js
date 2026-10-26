const path = require("path");
const util = require("@openremote/util");
const {rspack} = require("@rspack/core");

module.exports = (env, argv) => {

    const customConfigDir = env.config;
    const managerUrl = env.managerUrl;
    const keycloakUrl = env.keycloakUrl;
    const IS_DEV_SERVER = process.argv.find(arg => arg.includes("serve"));
    const config = util.getAppConfig(argv.mode, IS_DEV_SERVER, __dirname, managerUrl, keycloakUrl);

    if (IS_DEV_SERVER && customConfigDir) {
        console.log("CUSTOM_CONFIG_DIR: " + customConfigDir);
        // Try and include the static files in the specified config dir if we're in dev server mode
        config.plugins.push(new rspack.CopyRspackPlugin({
            patterns: [
                {
                    from: customConfigDir
                },
            ]
        }));
    }

    config.plugins.push(new rspack.CopyRspackPlugin({
        patterns: [
            { from: 'locales', to: 'locales' }
        ]
    }));

    // The built-in appearance page imports the menus from the built-in app's index, which would also
    // start that app next to this one (two managers, a login loop). Point those imports at this app's index.
    const builtInLib = path.dirname(require.resolve("@openremote/manager/headers"));
    const builtInIndex = path.join(builtInLib, "index");
    config.plugins.push(new rspack.NormalModuleReplacementPlugin(/^(\.\.\/)+index$/, (resource) => {
        if (path.resolve(resource.context, resource.request) === builtInIndex) {
            resource.request = path.resolve(__dirname, "src/index.ts");
        }
    }));

    // Add a custom base URL to resolve the config dir to the path of the dev server not root
    config.plugins.push(
        new rspack.DefinePlugin({
            CONFIG_URL_PREFIX: JSON.stringify(IS_DEV_SERVER && customConfigDir ? "/manager" : ""),
            // The appearance page shows the manager version, defined by the built-in app's build
            APP_VERSION: JSON.stringify(require(path.join(builtInLib, "../../package.json")).version)
        })
    );

    return config;
};
