# Custom UI apps

In this folder, you can add custom (web) applications that will be shipped along with OpenRemote.  
For example, special mobile apps for end users, or apps for less-technical consumers are widespread.  

Developing these custom apps is pretty straightforward, thanks to the built-in packages we provide.  
These make communicating with OpenRemote easier, and allows developers to quickly set up a user interface.

## Example apps

We provided several example apps to get familiar with the architecture.  
All apps can be run using `npm run serve`, and visited at `http://localhost:9000/<your folder name>/`.  
Here's a list of the apps, and what they do;

### /fleet
The IoT Watch manager app, built with [Lit Web Components](https://lit.dev) and [RSPack](https://rspack.rs).  
It is the OpenRemote manager app with the fleet search and overview page added as a "Fleet" main menu item.  
It is deployed to `{customAppDocroot}/manager`, where the manager serves it at `/manager` instead of the built-in app.  
`src/index.ts` copies the upstream manager app's entry point; keep it in step when upgrading OpenRemote.

### /custom-react
This is an example web application built with [React 19](https://react.dev) and [RSPack](https://rspack.rs).  
*(more information soon)*