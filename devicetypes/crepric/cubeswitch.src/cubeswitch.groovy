/*
 * Cube switch. Author: crepric@gmail.com
 *
 * Work based on the SmartSense Multisensor template:
 *    Author: Devide Handler
 *    Copyright 2016 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

import physicalgraph.zigbee.zcl.DataType

metadata {
	definition (name: "CubeSwitch", namespace: "crepric", author: "Riccardo Crepaldi") {
		capability "Acceleration Sensor"
		capability "Three Axis"
		capability "Sensor"
        capability "Configuration"
		capability "Battery"
		capability "Temperature Measurement"

		fingerprint profileId: "0104", inClusters: "0006, 0004, 0003, 0000, 0005", outClusters: "0019", manufacturer: "Compacta International, Ltd", model: "ZBMPlug15", deviceJoinName: "SmartPower Outlet V1"
	    attribute "currentFace", "number"
    }

	// simulator metadata
	simulator {
	}

	tiles(scale: 2) {
		valueTile("currentFace", "device.currentFace", inactiveLabel: false, width: 2, height: 2) {
			state "3Axis", label: '${currentValue}', unit: ""
		}
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label: '${currentValue}% battery', unit: ""
		}
        standardTile("acceleration", "device.acceleration", width: 2, height: 2) {
			state("active", label: 'Active', icon: "st.motion.acceleration.active", backgroundColor: "#53a7c0")
			state("inactive", label: 'Inactive', icon: "st.motion.acceleration.inactive", backgroundColor: "#ffffff")
		}
        valueTile("temperature", "device.temperature", width: 2, height: 2) {
			state("temperature", label: '${currentValue}°' ,
					backgroundColors: [
							[value: 31, color: "#153591"],
							[value: 44, color: "#1e9cbb"],
							[value: 59, color: "#90d2a7"],
							[value: 74, color: "#44b621"],
							[value: 84, color: "#f1d801"],
							[value: 95, color: "#d04e00"],
							[value: 96, color: "#bc2323"]
					]
			)
		}
		main  "currentFace", "acceleration"
		details "currentFace", "battery", "acceleration", "temperature"
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
    log.debug "Parsing ${description}"
	def maps = []
	maps << zigbee.getEvent(description)
    log.debug maps
	if (!maps[0]) {
		maps = []
		if (description?.startsWith('zone status')) {
			// maps += parseIasMessage(description)
		} else {
			Map descMap = zigbee.parseDescriptionAsMap(description)
			if (descMap?.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
			    maps << getBatteryResult(Integer.parseInt(descMap.value, 16))
			} else if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
				if (descMap.data[0] == "00") {
					log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
					sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
				} else {
					log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
				}
			} else {
				maps += handleAcceleration(descMap)
			}
		}
	} else if (maps[0].name == "temperature") {
		def map = maps[0]
		if (tempOffset) {
			map.value = (int) map.value + (int) tempOffset
		}
		map.descriptionText = map.unit == 'C' ? "${device.displayName} was ${map.value}°C" : "${device.displayName} was ${map.value}°F"
		map.translatable = true
        log.debug map
	}

	def result = maps.inject([]) {acc, it ->
		if (it) {
			acc << createEvent(it)
		}
	}
	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		log.debug "enroll response: ${cmds}"
		result = cmds?.collect { new physicalgraph.device.HubAction(it) }
	}
    log.debug result
	return result
}

private List<Map> handleAcceleration(descMap) {
	def result = []
    if (state.last_sent_face == null) {
    	state.last_sent_face = 0
    }
    if (state.current_face == null) {
    	state.current_face = 0
    }
	if (descMap.clusterInt == 0xFC02 && descMap.attrInt == 0x0010) {
		def value = descMap.value == "01" ? "active" : "inactive"
		log.debug "Acceleration $value"
        if (value == "active") {
        	state.active = true
            if (descMap.additionalAttrs) {
				parseAxis(descMap.additionalAttrs)
			}
        } else {
            log.debug("Inactive " + state.current_face)
//        	result << [
//			name           : "currentFace",
//			value          : state.current_face,
//			linkText       : getLinkText(device),
//			descriptionText: "${getLinkText(device)} was ${value}",
//			handlerName    : name,
//			isStateChange  : (state.last_sent_face != state.current_face)
//	        ]
            state.last_sent_face = 0
            state.active = false
        }
        log.debug(value)
		result << [
				name           : "acceleration",
				value          : value,
				descriptionText: "{{ device.displayName }} was $value",
				isStateChange  : isStateChange(device, "acceleration", value),
				translatable   : true
		]


	} else if (descMap.clusterInt == 0xFC02 && descMap.attrInt == 0x0012) {
		def addAttrs = descMap.additionalAttrs
		addAttrs << ["attrInt": descMap.attrInt, "value": descMap.value]
		state.current_face = parseAxis(addAttrs)
        log.debug("sending " + state.current_face)
        result << [
			name           : "currentFace",
			value          : state.current_face,
			linkText       : getLinkText(device),
			descriptionText: "${getLinkText(device)} was ${value}",
			handlerName    : name,
			isStateChange  : (state.last_sent_face != state.current_face)
	    ]
        state.last_sent_face = state.current_face
	}
	return result
}

private Integer parseAxis(List<Map> attrData) {
	def results = []
	def x = hexToSignedInt(attrData.find { it.attrInt == 0x0012 }?.value)
	def y = hexToSignedInt(attrData.find { it.attrInt == 0x0013 }?.value)
	def z = hexToSignedInt(attrData.find { it.attrInt == 0x0014 }?.value)
	def xyzResults = [:]
	xyzResults.x = z
	xyzResults.y = x
	xyzResults.z = y
	log.debug "parseAxis -- ${xyzResults}"
	def value = "${xyzResults.x},${xyzResults.y},${xyzResults.z}"
    def max_value_coordinate  = xyzResults.max { Math.abs(it.value) }.key
    def sign = xyzResults[max_value_coordinate] > 0
    def scene_code = state.current_face
    if (max_value_coordinate == 'x') {
        scene_code = sign?1:6
    } else if (max_value_coordinate == 'y') {
        scene_code = sign?2:5
    } else if (max_value_coordinate == 'z') {
        scene_code = sign?4:3
    }
    state.current_face = scene_code
//  log.debug "newFace -- ${scene_code} " + (state.current_face != scene_code)
    state.current_face = scene_code
	return scene_code
}

private Map getBatteryResult(rawValue) {
	log.debug "Battery rawValue = ${rawValue}"
	def result = [:]
	def volts = rawValue / 10
	if (!(rawValue == 0 || rawValue == 255)) {
		result.name = 'battery'
		result.translatable = true
		result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"
		if (device.getDataValue("manufacturer") == "SmartThings") {
			volts = rawValue // For the batteryMap to work the key needs to be an int
			def batteryMap = [28: 100, 27: 100, 26: 100, 25: 90, 24: 90, 23: 70,
							  22: 70, 21: 50, 20: 50, 19: 30, 18: 30, 17: 15, 16: 1, 15: 0]
			def minVolts = 15
			def maxVolts = 28
			if (volts < minVolts)
				volts = minVolts
			else if (volts > maxVolts)
				volts = maxVolts
			def pct = batteryMap[volts]
			result.value = pct
		} else {
			def minVolts = 2.1
			def maxVolts = 3.0
			def pct = (volts - minVolts) / (maxVolts - minVolts)
			def roundedPct = Math.round(pct * 100)
			if (roundedPct <= 0)
				roundedPct = 1
			result.value = Math.min(100, roundedPct)
		}
	}
	return result
}

private hexToSignedInt(hexVal) {
	def unsignedVal = hexToInt(hexVal)
	unsignedVal > 32767 ? unsignedVal - 65536 : unsignedVal
}

private hexToInt(value) {
	new BigInteger(value, 16)
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	return zigbee.readAttribute(0x001, 0x0020) // Read the Battery Level
}

def configure() {
	// Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	// enrolls with default periodic reporting until newer 5 min interval is confirmed
	sendEvent(name: "checkInterval",
    		  value: 2 * 60 * 60 + 1 * 60,
              displayed: false, 
              data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

	log.debug "Configuring Reporting"
	def configCmds = []

	//if (device.getDataValue("manufacturer") == "SmartThings") {
		log.debug "Refreshing Values for manufacturer: SmartThings "
		/* These values of Motion Threshold Multiplier(0x01) and Motion Threshold (0x0276)
            seem to be giving pretty accurate results for the XYZ co-ordinates for this manufacturer.
            Separating these out in a separate if-else because I do not want to touch Centralite part
            as of now.
        */
		configCmds += zigbee.writeAttribute(0xFC02, 0x0000, 0x20, 0x01, [mfgCode: manufacturerCode])
		configCmds += zigbee.writeAttribute(0xFC02, 0x0002, 0x21, 0x0276, [mfgCode: manufacturerCode])
	//} else {
		// Write a motion threshold of 2 * .063g = .126g
		// Currently due to a Centralite firmware issue, this will cause a read attribute response that
		// indicates acceleration even when there isn't.
	//	configCmds += zigbee.writeAttribute(0xFC02, 0x0000, 0x20, 0x02, [mfgCode: manufacturerCode])
	//}

	// temperature minReportTime 30 seconds, maxReportTime 5 min. Reporting interval if no activity
	// battery minReport 30 seconds, maxReportTime 6 hrs by default
	configCmds += zigbee.batteryConfig() +
			zigbee.temperatureConfig(30, 300) +
			zigbee.configureReporting(0xFC02, 0x0010, DataType.BITMAP8, 10, 3600, 0x01, [mfgCode: manufacturerCode]) +
			zigbee.configureReporting(0xFC02, 0x0012, DataType.INT16, 1, 3600, 0x0001, [mfgCode: manufacturerCode]) +
			zigbee.configureReporting(0xFC02, 0x0013, DataType.INT16, 1, 3600, 0x0001, [mfgCode: manufacturerCode]) +
			zigbee.configureReporting(0xFC02, 0x0014, DataType.INT16, 1, 3600, 0x0001, [mfgCode: manufacturerCode])

	return refresh() + configCmds
}

def refresh() {
	log.debug "Refreshing Values "
	def refreshCmds = zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
			zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
			zigbee.readAttribute(0xFC02, 0x0010, [mfgCode: manufacturerCode]) +
			zigbee.enrollResponse()
	return refreshCmds
}