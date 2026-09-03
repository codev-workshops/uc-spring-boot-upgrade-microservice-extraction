package contracts.user

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "POST /internal/users stores the caller-supplied id and passwordHash anonymously and returns 201"
    request {
        method POST()
        url "/internal/users"
        headers {
            contentType applicationJson()
        }
        body(id: "user-new", username: "newbie", email: "new@example.com", passwordHash: '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', bio: "", image: "https://example.com/i.png")
    }
    response {
        status CREATED()
        headers {
            contentType applicationJson()
        }
        body(user: [id: "user-new", username: "newbie", email: "new@example.com", bio: "", image: "https://example.com/i.png"])
    }
}
