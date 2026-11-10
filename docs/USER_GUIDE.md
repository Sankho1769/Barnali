# Community member guide

This guide describes source-supported paths. Actual account operations require the API and PostgreSQL service to be configured.

## Browse and discover

- Open `/` for the general latest discussion feed.
- Open `/explore` for popular discussions and public rooms.
- Use `/discussions` to search/sort discussions, or `/rooms` to browse rooms.
- Public discussion and room detail routes can be read without an account.

## Join and participate

1. Create an account at `/register`, then sign in at `/login`.
2. Open a room and choose Join. A member can publish discussions only in a room they have joined.
3. Open a discussion to reply, vote, or submit a report. Sensitive actions depend on the backend response; the UI displays errors rather than claiming success if the API fails.
4. View your account summary at `/profile` and change your password at `/account/password`.

## Password recovery

Use `/forgot-password` and the generic recovery response. A reset message is delivered only if SMTP is configured and the account is eligible. Do not request or share an administrator's permanent password.

## Not currently available

Bookmarks, following, personalized feeds, private messaging, notifications, blocking, realtime updates, public profiles, and email verification are not implemented. The “For you” feed tab currently points to the general feed, not a personalized ranking.
