package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "liveness used by the cutover runbook"
    request {
        method GET()
        url "/actuator/health"
        headers { accept applicationJson() }
    }
    response {
        status OK()
        body(status: "UP")
    }
}
