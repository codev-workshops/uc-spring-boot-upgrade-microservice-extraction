package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "update skips blank/null fields exactly like UserMapper.xml#update; requires the caller JWT whose sub == id"
    request {
        method PUT()
        url "/internal/users/u1000000-0000-0000-0000-000000000001"
        headers {
            contentType applicationJson()
            header "Authorization": $(consumer(regex("Token .+")), producer("Token valid-jwt-for-u1"))
        }
        body(
                username: "",
                email: "john@jacob.com",
                passwordHash: "",
                bio: "bio",
                image: ""
        )
    }
    response {
        status OK()
        headers { contentType applicationJson() }
        body(user: [id: "u1000000-0000-0000-0000-000000000001", username: "john", email: "john@jacob.com", bio: "bio", image: "img"])
    }
}
