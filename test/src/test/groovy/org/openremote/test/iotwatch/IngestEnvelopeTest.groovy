package org.openremote.test.iotwatch

import org.openremote.model.iotwatch.IngestEnvelope
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class IngestEnvelopeTest extends Specification {

    static final String EXAMPLE = '''
    {
      "data": "{\\"cmd\\":\\"gw\\",\\"ts\\":1773397633008,\\"data\\":\\"cbe006e001c10106aa7fff\\",\\"EUI\\":\\"00112233AABBCCDD\\"}",
      "tags": [],
      "tech": "L",
      "type": "D"
    }
    '''

    def "deserializes the platform integration envelope"() {
        when:
        def envelope = ValueUtil.JSON.readValue(EXAMPLE, IngestEnvelope)

        then:
        envelope.type == "D"
        envelope.tech == "L"
        envelope.tags == []
        envelope.data.contains('"EUI":"00112233AABBCCDD"')
    }

    def "ignores unknown envelope fields"() {
        when:
        def envelope = ValueUtil.JSON.readValue('{"data":"{}","type":"D","tech":"L","tags":[],"extra":1}', IngestEnvelope)

        then:
        envelope.data == "{}"
    }
}
