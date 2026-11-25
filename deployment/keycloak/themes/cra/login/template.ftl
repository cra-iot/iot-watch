<#--
    CRA IoT Watch page layout. Derived from the Keycloak base theme template.ftl (26.7):
    the macro signature, nested sections and session scripts must stay compatible with the
    base page templates, which all render through this layout.

    On login.ftl and login-username.ftl a "CRA account" provider block above the local form
    replaces the base socialProviders section (nested "socialProviders" is skipped there).
    Check it against the base login.ftl on Keycloak upgrades.

    The override intentionally does not import theme-resources.ftl or use the base themeResources
    branch: a child theme must set styles, scripts and favicon through theme.properties.
-->
<#import "footer.ftl" as loginFooter>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<#assign realmName = (realm.displayName?has_content)?then(realm.displayName, realm.name)>
<#assign craSsoBlock = realm.password && social?? && social.providers?has_content && (pageId == "login" || pageId == "login-username")>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="robots" content="noindex, nofollow">
    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("craPageTitle", realmName)}</title>
    <link rel="icon" type="image/png" href="${url.resourcesPath}/img/favicon.png" />
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <script src="${url.resourcesPath}/js/menu-button-links.js" type="module"></script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");

            if (!link) {
                return;
            }

            if (link.getAttribute("aria-disabled") === "true") {
                event.preventDefault();
                return;
            }

            const { disabledClass } = link.dataset;

            if (disabledClass) {
                link.classList.add(...disabledClass.trim().split(/\s+/));
            }

            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
            <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!} ${bodyClass}" data-page-id="login-${pageId}">
<div class="${properties.kcLoginClass!}">

    <header class="cra-topbar">
        <div class="cra-topbar__brand">
            <img class="cra-topbar__logo" src="${url.resourcesPath}/img/cra-logo.svg" alt="CRA" width="81" height="27">
            <span class="cra-topbar__divider" aria-hidden="true"></span>
            <span class="cra-topbar__product">
                <span class="cra-pulse" aria-hidden="true"></span>
                ${msg("craProductName")}
            </span>
        </div>

        <#if realm.internationalizationEnabled && locale.supported?size gt 1>
            <div class="${properties.kcLocaleMainClass!}" id="kc-locale">
                <div id="kc-locale-wrapper" class="${properties.kcLocaleWrapperClass!}">
                    <div id="kc-locale-dropdown" class="menu-button-links ${properties.kcLocaleDropDownClass!}">
                        <button tabindex="1" id="kc-current-locale-link" aria-label="${msg("languages")}" aria-haspopup="true" aria-expanded="false" aria-controls="language-switch1">${locale.current}</button>
                        <ul role="menu" tabindex="-1" aria-labelledby="kc-current-locale-link" aria-activedescendant="" id="language-switch1" class="${properties.kcLocaleListClass!}">
                            <#assign i = 1>
                            <#list locale.supported as l>
                                <li class="${properties.kcLocaleListItemClass!}" role="none">
                                    <a role="menuitem" id="language-${i}" class="${properties.kcLocaleItemClass!}" href="${l.url}">${l.label}</a>
                                </li>
                                <#assign i++>
                            </#list>
                        </ul>
                    </div>
                </div>
            </div>
        </#if>
    </header>

    <main class="cra-main">
        <#-- Decorative device network behind the content -->
        <svg class="cra-network" viewBox="0 0 1200 600" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false">
            <g class="cra-network__links">
                <path d="M80 120 L230 210 L180 380 L330 470 M230 210 L390 150 M180 380 L60 500"/>
                <path d="M1120 90 L960 180 L1030 340 L880 450 M960 180 L820 120 M1030 340 L1150 460"/>
            </g>
            <g class="cra-network__nodes">
                <circle cx="80" cy="120" r="4"/><circle cx="390" cy="150" r="4"/><circle cx="60" cy="500" r="4"/>
                <circle cx="330" cy="470" r="5"/><circle cx="1120" cy="90" r="4"/><circle cx="820" cy="120" r="4"/>
                <circle cx="880" cy="450" r="5"/><circle cx="1150" cy="460" r="4"/>
            </g>
            <g class="cra-network__hubs">
                <circle cx="230" cy="210" r="7"/><circle cx="180" cy="380" r="6"/>
                <circle cx="960" cy="180" r="7"/><circle cx="1030" cy="340" r="6"/>
            </g>
        </svg>

        <div class="cra-heading">
            <h1 id="kc-page-title"><#nested "header"></h1>
        </div>

        <div class="${properties.kcFormCardClass!}">
            <div class="cra-realm">
                <span class="cra-realm__icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" focusable="false">
                        <circle cx="12" cy="17" r="2"/>
                        <path d="M8.2 13.2a5.4 5.4 0 0 1 7.6 0M5.3 10.3a9.5 9.5 0 0 1 13.4 0M2.5 7.4a13.5 13.5 0 0 1 19 0"/>
                    </svg>
                </span>
                <div class="cra-realm__text">
                    <span class="cra-realm__label">${msg("craRealmLabel")}</span>
                    <span class="cra-realm__name">${realmName}</span>
                </div>
            </div>

            <div class="cra-card__header">
            <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
                <#nested "show-username">
                <div id="kc-username" class="cra-attempted-username">
                    <span id="kc-attempted-username">${auth.attemptedUsername}</span>
                    <a id="reset-login" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}" title="${msg("restartLoginTooltip")}">
                        <i class="${properties.kcResetFlowIcon!}" aria-hidden="true"></i>
                    </a>
                </div>
            </#if>
            <#if displayRequiredFields>
                <p class="cra-required-note"><span class="required">*</span> ${msg("requiredFields")}</p>
            </#if>
            </div>

            <div id="kc-content"<#if craSsoBlock> class="cra-content--sso"</#if>>
                <#-- App-initiated actions should not see warning messages about the need to complete the action during login. -->
                <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                    <div class="${properties.kcAlertClass!} cra-alert--${message.type}" role="alert">
                        <#if message.type = 'success'><span class="${properties.kcFeedbackSuccessIcon!}" aria-hidden="true"></span></#if>
                        <#if message.type = 'warning'><span class="${properties.kcFeedbackWarningIcon!}" aria-hidden="true"></span></#if>
                        <#if message.type = 'error'><span class="${properties.kcFeedbackErrorIcon!}" aria-hidden="true"></span></#if>
                        <#if message.type = 'info'><span class="${properties.kcFeedbackInfoIcon!}" aria-hidden="true"></span></#if>
                        <span class="${properties.kcAlertTitleClass!}">${kcSanitize(message.summary)?no_esc}</span>
                    </div>
                </#if>

                <#if craSsoBlock>
                    <section class="cra-sso">
                        <#assign craHasSsoProvider = false>
                        <#list social.providers as p><#if p.alias == "cra-sso"><#assign craHasSsoProvider = true></#if></#list>
                        <h2 class="cra-sso__heading">${msg(craHasSsoProvider?then("craAccountHeading", "identity-provider-login-label"))}</h2>
                        <#list social.providers as p>
                            <a data-once-link data-disabled-class="${properties.kcFormSocialAccountListButtonDisabledClass!}" id="social-${p.alias}"
                               class="cra-btn cra-btn--primary cra-btn--block cra-sso__button" href="${p.loginUrl}"><#if p.alias == "cra-sso">${msg("craAccount")}<#else>${p.displayName!}</#if></a>
                        </#list>
                    </section>
                    <div class="cra-or"><span>${msg("craOr")}</span></div>
                    <h2 class="cra-local__heading">${msg("craLocalAccountHeading")}</h2>
                </#if>

                <#nested "form">

                <#if auth?has_content && auth.showTryAnotherWayLink()>
                    <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                        <div class="${properties.kcFormGroupClass!}">
                            <input type="hidden" name="tryAnotherWay" value="on"/>
                            <a href="#" id="try-another-way"
                               onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                        </div>
                    </form>
                </#if>

                <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
                    <form id="kc-switch-organization-form" action="${url.loginAction}" method="post">
                        <div class="${properties.kcFormGroupClass!}">
                            <input type="hidden" name="switchOrganization" value="true"/>
                            <a href="#" id="switch-organization"
                               onclick="document.forms['kc-switch-organization-form'].requestSubmit();return false;">${msg("doSwitchOrganization")}</a>
                        </div>
                    </form>
                </#if>

                <#if !craSsoBlock>
                    <#nested "socialProviders">
                </#if>

                <#if displayInfo>
                    <div id="kc-info" class="${properties.kcSignUpClass!}">
                        <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                            <#nested "info">
                        </div>
                    </div>
                </#if>
            </div>

            <@loginFooter.content/>
        </div>
    </main>

    <footer class="cra-footer">
        &copy; ${.now?string("yyyy")} České Radiokomunikace a.s.
    </footer>
</div>
</body>
</html>
</#macro>
