Feature: Order tracking API

  Scenario: Register and track an order
    When I register order "o-1001" for customer "c-2001"
    Then the response status is 201
    And the response contains order "o-1001" with status "CREATED"
    And the response contains customer "c-2001"
    And a status change event was published
    When I track order "o-1001"
    Then the response status is 200
    And the response contains order "o-1001" with status "CREATED"

  Scenario: Move an order through a valid status transition
    Given an existing order:
      | orderId | customerId |
      | o-2001  | c-3001     |
    When I update order "o-2001" to status "PACKED" with note "Packed in warehouse"
    Then the response status is 200
    And the response contains order "o-2001" with status "PACKED"
    And the order history contains status "PACKED" with note "Packed in warehouse"
    And a status change event was published

  Scenario: Reject an invalid status transition
    Given an existing order:
      | orderId | customerId |
      | o-3001  | c-4001     |
    When I update order "o-3001" to status "DELIVERED" with note "Skipped states"
    Then the response status is 409
    And the problem title is "Business rule violation"
    And the problem detail contains "Invalid transition from CREATED to DELIVERED"

  Scenario: List orders by status
    Given an existing order:
      | orderId | customerId |
      | o-4001  | c-5001     |
    And order "o-4001" has status "PACKED"
    Given an existing order:
      | orderId | customerId |
      | o-4002  | c-5002     |
    When I list orders filtered by status "PACKED"
    Then the response status is 200
    And the listed orders are:
      | orderId | customerId | status |
      | o-4001  | c-5001     | PACKED |

  Scenario: Require authentication for API access
    Given an existing order:
      | orderId | customerId |
      | o-5001  | c-6001     |
    When I track order "o-5001" without credentials
    Then the response status is 401
