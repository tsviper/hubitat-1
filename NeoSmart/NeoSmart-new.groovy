/*
 * Neo Smart Controller
 *
 * Calls URIs with HTTP GET for shade open/close/stop/favourite using the Neo Smart Controller
 * 
 * Assumption: The blinds (or room of blinds) you're controlling all take the 
 * same amount of time to open/close (some may be a little faster/slower than
 * the others, but they should all generally take the same amount of time to
 * otherwise, create another virtual driver for ones that are slower/faster). 
 * 
 * To use: 
 * 1) Run a stopwatch to time how long it takes for the blinds to close (in
 * seconds)
 * 2) Open the blinds completely then time how long it takes to get to your
 * favorite setting) 
 * 
 * Input these values in the configuration, rounding down.  Your device will
 * like a dimmable bulb to Alexa, thus you should be able to say "Alexa, turn
 * the living room blinds to 50%" and it will go in the middle (or close). 
 *
 * Keep in mind, in order for this to work you have to always control the
 * blinds through Hubitat and not the Neo App or RF remote. 
 *  
 * Based on the Hubitat community driver httpGetSwitch and initial
 * contributions and testing from bigrizz and others via Hubitat community:
 * https://community.hubitat.com/t/neo-smart-controller-for-blinds/.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 * 
 * Version   1: bigrizz - initial commits 
 *         1.1: bdwilson - parameterized inputs & preliminary level settings, fixed time stamp
 *           2: Fixed level, added initial timing configs for up/down/stop control to work along with level 
 */
metadata {
    definition(name: "Neo Smart Controller-alpha", namespace: "bigrizzo", author: "bigrizz", importUrl: "https://raw.githubusercontent.com/bdwilson/hubitatDrivers/master/NeoSmart.groovy") {
        capability "WindowShade"
		capability "Switch"
		capability "Actuator"
	 	capability "ChangeLevel"   
		capability "Switch Level"
		
		command "stop"
		command "favorite"
		command "up"
		command "down"
    }
}

preferences {
    section("URIs") {
		input "blindCode", "text", title: "Blind or Room Code (from Neo App)", required: true
		input "controllerID", "text", title: "Controller ID (from Neo App)", required: true
		input "controllerIP", "text", title: "Controller IP Address (from Neo App)", required: true
        input "timeToClose", "number", title: "Time in seconds it takes to close the blinds completely", required: true
        input "timeToFav", "number", title: "Time in seconds it takes to reach your favorite setting when closing the blinds", required: true, defaultValue: 0
        input name: "stopOnDouble", type: "bool", title: "Stop on the second press of open or close?", defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def date() {
	//def date = new Date().getTime().toString().drop(6)
    def origdate = new Date().getTime().toString().drop(6)  // API docs say only 7 chars 
    def random = Math.floor(Math.random() * 1000)
   // log.debug "First random: ${random}"
    random = random.toInteger().toString().take(4) // get four random #'s
   // log.debug "origdate: ${origdate} random: ${random}"
    def date = origdate.toInteger() + random.toInteger()    // add 4 random #'s to millisecs
    //if (logEnable) log.debug "Using ${date}"
	return date
}

def get(url,mystate) {
   log.debug "Call to ${url}; setting ${mystate}"
   state.lastCmd = mystate
   try {
        //httpGet(url) { resp ->
        //    if (resp.success) {
                sendEvent(name: "windowShade", value: "${mystate}", isStateChange: true)
        //   }
         //  if (logEnable)
         //       if (resp.data) log.debug "${resp.data}"
        //}
    } catch (Exception e) {
        log.warn "Call to ${url} failed: ${e.message}"
    }
}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def installed() {
    log.info "installed..."
	if (!controllerID || !controllerIP || !blindCode || !timeToClose || !timeToFav) {
		log.error "Please make sure controller ID, IP, blind/room code, time to close and time to favorite are configured." 
	}
    log.warn "debug logging is: ${logEnable == true}"
    if (logEnable) runIn(1800, logsOff)
}

def updated() {
    log.info "updated..."
	if (!controllerID || !controllerIP || !blindCode || !timeToClose || !timeToFav) {
		log.error "Please make sure controller ID, IP, blind/room code, time to close and time to favorite are configured." 
	}
    log.warn "debug logging is: ${logEnable == true}"
    if (logEnable) runIn(1800, logsOff)
}

def parse(String description) {
    if (logEnable) log.debug(description)
}

def up() {
	startLevelChange("up")
}

def down() {
	startLevelChange("down")
}
	
def on() {
	open() 
}

def off() {
	close()
}

def close() {
    url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-dn&id=" + controllerID + "&hash=" + date()
    
    if (stopOnDouble && (state.lastCmd == "closing")) {
       stop()
    } else if (state.lastCmd != "closing") {
        
        UpdateTimeRunning()
    
        def closingTime = timeToClose
   
        if (state.lastCmd == "opening") {  // someone went from open to close without stopping. how do we deal?  Change to call stop() later?
            log.debug("Status was opening, closing was hit. scheduling closingTime for ${state.secs}")
            unschedule(updateStatus)
            closingTime = state.secs
        } else if (state.lastCmd == "stopped") { // we're partially open
            closingTime = timeToClose - state.secs
            log.debug("Going from Stopped to Closing. closingTime will now be: ${closingTime}")
        } else if (state.lastCmd == "closed") {
            log.debug("Status is closed, close was hit. unscheduling all and updating status immediately");
	        unschedule(updateStatus)
            closingTime=0
        } else {
            log.debug("else called in close, lastCmd is ${state.lastCmd}, setting closingTime to timeToClose")
            closingTime = timeToClose
        }   
        if (logEnable) log.debug "Sending close GET request to ${url}"  
        get(url,"closing")
    
        log.debug("Closing... timeToClose: ${closingTime}")
    
        closingTime = closingTime * 1000
        runInMillis(closingTime.toInteger(),updateStatus,[data: [status: "closed", level: 0, secs: timeToClose]])
    }
}

def open() {
    url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-up&id=" + controllerID + "&hash=" + date()
    
    if (stopOnDouble && (state.lastCmd == "opening")) {
        stop()
    } else if (state.lastCmd != "opening") {
        
        UpdateTimeRunning()    
    
        def closingTime = timeToClose
    
        if (state.lastCmd == "closing") {
            unschedule(updateStatus)
            log.debug("Status was closing, open was hit - scheduling closingTime for ${state.secs}")
            closingTime = state.secs
        } else if (state.lastCmd == "stopped") { // we're partially open
            closingTime = state.secs
            log.debug("Going from Stopped to Opening. closingTime will now be: ${closingTime}")
            //closingTime = state.secs
        } else if (state.lastCmd == "opened") {
            log.debug("Status is open, open was hit. unscheduling all and updating status immediately");
            unschedule(updateStatus)
            closingTime=0
        } else {
            log.debug("else called in open, lastCmd is ${state.lastCmd}, setting closingTime to timeToClose")
            closingTime = timeToClose
        }       
    
        if (logEnable) log.debug "Sending open GET request to ${url}"
	    get(url,"opening")
   
        log.debug("Opening... timeToOpen: ${closingTime}")
  
        closingTime = closingTime * 1000
        runInMillis(closingTime.toInteger(),updateStatus,[data: [status: "opened", level: 100, secs: 0]])
    }
}

def updateStatus(data) {
    //log.debug("DATA: ${data}")
    state.level = data.level
    state.lastCmd = data.status
    state.secs = data.secs
    log.debug("Running updateStatus: status: ${state.lastCmd}, level: ${state.level}, secs: ${state.secs}")
    sendEvent(name: "level", value: "${state.level}", isStateChange: true)
    sendEvent(name: "windowShade", value: "${state.lastCmd}", isStateChange: true)
}

def stop() {
    url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-sp&id=" + controllerID + "&hash=" + date()
    
    unschedule(updateStatus)
    
    UpdateTimeRunning()
	
    if (logEnable) log.debug "Sending stop GET request to ${url}"
    get(url,"partially open")
    state.level=100-((state.secs/timeToClose)*100).toInteger()
    state.data = [status: "partially open", level: state.level, secs: state.secs]
    updateStatus(state.data)
    //updateStatus("partially open", state.level, state.secs) 
    state.lastCmd = "stopped"
}

def UpdateTimeRunning() {
    def now = new Date().getTime()
    def timeRunningSecs = null 
    def timeRunning = null 
    if (state.stateChangeTime) {
        timeRunning = now.toInteger()-state.stateChangeTime.toInteger()
        //log.debug "Found that ${now} - ${state.stateChangeTime} = ${timeRunning}"
        timeRunningSecs = timeRunning/1000 // convert MS to Secs
        log.debug "Found that ${now} - ${state.stateChangeTime} = ${timeRunning} ($timeRunningSecs)"

    }
    // if state.stateChangeTime is beyond how long it takes to open/close completely
    // then we'll assume it's already been successful at that open/close command. 
    //if (timeRunningSecs > timeToClose) { 
    if ((timeRunningSecs > timeToClose) || (state.lastCmd == "opened") || (state.lastCmd == "closed") || (state.lastCmd == "stopped")) { 
        log.debug "Found that ${timeRunningSecs} > ${timeToClose} OR lastCmd was opened/closed/stopped"
        log.debug "Resetting state.stateChangeTime = ${now}, lastCmd: ${state.lastCmd}"
        state.stateChangeTime = now
        //state.timeRan=0 
    } else if ((state.stateChangeTime < now) && (timeRunningSecs != null)) {
        //def timeRunning = now - state.stateChangeTime.toInteger()
        //state.timeRan = timeRunningSecs
        log.debug "UpdateTimeRunning- Time Running: ${timeRunningSecs} Old State.secs: ${state.secs} Last Cmd: ${state.lastCmd}"

        //if ((state.secs != 0) && (state.secs != timeToClose)) { // 
            //if (state.lastCmd == "opened") {
            //    state.secs = 0
            //} else if (state.lastCmd == "closed") {
            //    state.secs = timeToClose
            //} else 
            if (state.lastCmd == "closing") {
                state.secs=timeRunningSecs + state.secs
            } else if (state.lastCmd == "opening") {
                state.secs=state.secs - timeRunningSecs
            }
        //}
        log.debug "UpdateTimeRunning- Time Running: ${timeRunningSecs} New State.secs: ${state.secs}"
    } else {
        log.debug "state.stateChangeTime = ${state.stateChangeTime} timeRunningSecs = ${timeRunningSecs}"
        log.debug "Setting state.stateChangeTime = ${now}"
        state.stateChangeTime = now
    }
}

def stopPosition(data) {
    url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-sp&id=" + controllerID + "&hash=" + date()
    if (logEnable) log.debug "Sending stop GET request to ${url}"
	get(url,"partially open")
    state.data = [status: "partially open", level: data.newposition, secs: state.secs]
    updateStatus(state.data)
    //sendEvent(name: "level", value: "${state.newposition}", isStateChange: true)
    log.debug "Stopped ${data.newposition} ${data.difference}"
    //state.level=state.newposition
    //state.difference=0
}

def runAndStop(data) {
    if (data.direction == "up") {
        url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-up&id=" + controllerID + "&hash=" + date()
    } else {
        url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-dn&id=" + controllerID + "&hash=" + date()
    }
    //if (logEnable) log.debug "Adjusting ${direction} to ${position} for ${secs} request to ${url}"
    log.debug "Adjusting ${data.direction} to ${data.newposition} for ${data.difference} request to ${url}"
    get(url,"partially open")   
    runInMillis(data.difference.toInteger(),stopPosition,[data: [newposition: data.newposition, difference: data.difference]])
}

def favorite() {
    url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-gp&id=" + controllerID + "&hash=" + date()
    if (logEnable) log.debug "Sending favorite GET request to ${url}"
    state.secs=timeToFav
    state.level=100-((timeToFav/timeToClose)*100)
	get(url,"partially open")
    sendEvent(name: "level", value: "${state.level}", isStateChange: true)
}

def setPosition(position) { // timeToClose= closed/down, 0=open/up
    //log.debug "Opening to ${blindCode} to ${position}"
    secs = timeToClose-((position/100)*timeToClose)  // get percentage based on how long it takes to open.     
    log.debug "Setting Position for ${blindCode} to ${position} (maps to travel time from open of ${secs} secs)"
    if (secs >= timeToClose) {
		secs = timeToClose
	}
    if (position != state.level)  {
        state.lastCmd="stopped"
        log.debug "Position: ${position}  StateLevel: ${state.level}  StateSecs: ${state.secs} Secs: ${secs} Last Cmd: ${state.lastCmd}"
            if ((position > state.level) || (state.secs > secs)) { // requested location is more closed than current. 
                if (position == 100) {
                    open()
                    //state.level=100
                    //state.secs=0
                } else {
                    pos = ((state.secs - secs) * 1000) //-2000
                    if (pos < 1000) {
                         pos = 1000
                    }
                    //state.direction = "up"
                    //state.difference = pos
                    //state.newposition = position
                    state.secs = secs
                    log.debug "Opening... Stopping at ${pos} secs"
                    runInMillis(10,runAndStop,[data: [direction: "up", difference: pos, newposition: position]])
                }
            } else {  // location is less closed than current
                if (position == 0) {
                    close()
                } else {
                    def pos = ((secs - state.secs)*1000) //-2000
                    if (pos < 1000) {
                         pos = 1000
                    }
                    state.secs = secs
                    log.debug "Closing... Stopping at ${pos} secs"
                    runInMillis(10,runAndStop,[data: [direction: "down", difference: pos, newposition: position]])
      
                }
            }
      }
}

def startLevelChange(direction) {
	// https://github.com/hubitat/HubitatPublic/blob/master/examples/drivers/genericComponentDimmer.groovy 
    if (direction == "up") {
        url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-mu&id=" + controllerID + "&hash=" + date()
    } else {
        url = "http://" + controllerIP + ":8838/neo/v1/transmit?command=" + blindCode + "-md&id=" + controllerID + "&hash=" + date()
    }
    if (logEnable) log.debug "Sending startLevel Change ${direction} GET request to ${url}"
    get(url,"partially open")
}

def setLevel(level) {
    setPosition(level)
}
