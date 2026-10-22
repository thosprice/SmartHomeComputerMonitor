/**
 *  Computer Monitor Parent - App for Hubitat Elevation hub
 *
 *  Minimal parent/manager app. Install this once; it lets you add any number of "Computer Monitor"
 *  child apps (one per lighting zone) from its own page, and those instances then nest under
 *  this single "Computer Monitor Parent" entry on the hub's Apps page instead of listing separately.
 *
 *  The child-app picker is hidden until this app has finished its own initial install and Done has been 
 *  clicked at least once). Hubitat doesn't register an app instance until that happens, so both parent and child
 *  apps can be left in limbo.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Ver. 1.0.0  2026-10-22  - first version
 */

definition(
    name: 'Computer Monitor Parent',
    namespace: 'tprice-custom',
    author: 'tprice',
    description: 'Parent app - add one "Computer Monitor" child below per computer you want to track.',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true
)

preferences {
    page(name: 'mainPage')
}

Map mainPage() {
    dynamicPage(name: 'mainPage', title: '', install: true, uninstall: true) {
        section {
	        if (state.appInstalled) {
            	    app(name: 'zoneApps', appName: 'Computer Monitor', namespace: 'tprice-custom', title: 'Add a new computer monitor app instance', multiple: true)
	        } else {
        	        paragraph 'Click <b>Done</b> below to finish installing this app. Come back in afterward and you\'ll be able to add zones from this page.'
	        }
            label title: "<br><br><br><b>App name:</b>", required: true

        }
    }
}

void installed() {
    state.appInstalled = true	// Tracking whether to allow the child app install prompt.
}

void updated() {
    state.appInstalled = true	// Tracking whether to allow the child app install prompt.
}
